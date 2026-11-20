package com.tokenledgercloud.api.domain.decision;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.ServerPriceCalculator;
import com.tokenledgercloud.api.domain.accounting.observe.LedgerSpendRepository;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshotService;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.Evaluation;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.PriceInput;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.Scope;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.ScopeInput;
import com.tokenledgercloud.api.domain.ingestion.config.ObserveIngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.policy.PolicyContextService;
import com.tokenledgercloud.api.domain.policy.PolicyContextService.ContextView;
import com.tokenledgercloud.api.domain.policy.PolicyVersionView;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Advisory 판정 API의 처리 흐름: 키 인증 → 멱등 키 조회 → 가격·정책 snapshot 묶기(capture) → 계산 → 저장.
 *
 * <p><b>트랜잭션 경계.</b> 이 서비스는 트랜잭션 안에서 실행하지 않는다. 각 단계(인증, capture, 저장, 충돌 후 조회)가
 * 자기 트랜잭션에서 커밋한다. 그래서 capture가 잡는 projects 행 잠금(SELECT ... FOR UPDATE)은 capture가 끝날 때 풀리고,
 * decision_records insert 시점에는 바깥 트랜잭션이 그 잠금을 쥐고 있지 않다. MySQL에서 insert의 FK 검사가 부모 행의
 * 공유 잠금을 기다리다 교착 나는 것을 구조로 막기 위해, 바깥 트랜잭션이 있으면 시작과 insert 직전에 거부한다.
 *
 * <p>판정은 권고다. 이 서비스는 provider 호출을 막지 않는다.
 */
@Slf4j
@Service
public class DecisionService {

	static final String SCHEMA_VERSION = "2026-10-01";

	private static final String SNAPSHOT_ACTOR_TYPE = "SYSTEM";
	private static final String SNAPSHOT_ACTOR_ID = "advisory-decision";
	private static final String UNIQUE_SQL_STATE = "23505";
	private static final int MYSQL_DUPLICATE_ENTRY = 1062;
	private static final ObjectMapper JSON = new ObjectMapper();

	private final ProjectApiKeyAuthenticator authenticator;
	private final PolicyContextService contexts;
	private final PricingPlanRepository pricingPlans;
	private final PricingSnapshotService pricingSnapshots;
	private final LedgerSpendRepository spend;
	private final ObserveIngestionProperties pricingProperties;
	private final DecisionRecordTx recordTx;
	private final Clock clock;

	@Autowired
	public DecisionService(ProjectApiKeyAuthenticator authenticator, PolicyContextService contexts,
		PricingPlanRepository pricingPlans, PricingSnapshotService pricingSnapshots, LedgerSpendRepository spend,
		ObserveIngestionProperties pricingProperties, DecisionRecordTx recordTx) {
		this(authenticator, contexts, pricingPlans, pricingSnapshots, spend, pricingProperties, recordTx, Clock.systemUTC());
	}

	DecisionService(ProjectApiKeyAuthenticator authenticator, PolicyContextService contexts,
		PricingPlanRepository pricingPlans, PricingSnapshotService pricingSnapshots, LedgerSpendRepository spend,
		ObserveIngestionProperties pricingProperties, DecisionRecordTx recordTx, Clock clock) {
		this.authenticator = authenticator;
		this.contexts = contexts;
		this.pricingPlans = pricingPlans;
		this.pricingSnapshots = pricingSnapshots;
		this.spend = spend;
		this.pricingProperties = pricingProperties;
		this.recordTx = recordTx;
		this.clock = clock;
	}

	/**
	 * @param idempotencyKey 8~128자의 불투명 문자열. 해석하지 않는다
	 * @throws ApiException 인증 실패(401·403·404), 같은 키의 다른 본문·같은 requestId의 다른 키(409 IDEMPOTENCY_CONFLICT),
	 *                      동시 요청 충돌을 풀지 못함(503, 같은 요청을 다시 보내면 안전)
	 */
	public DecisionResponse decide(String rawApiKey, String idempotencyKey, DecisionCommand command) {
		requireNoOuterTransaction();
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "Idempotency-Key must be 8 to 128 characters.");
		}

		// 1. 키 인증. 본문에 projectKey가 없으므로 프로젝트는 키의 프로젝트다.
		AuthenticatedProjectApiKey key = authenticator.authenticate(rawApiKey, command.environment());
		String fingerprint = DecisionFingerprint.of(command);

		// 2. 멱등 키 조회. 같은 지문이면 처음 응답을 그대로, 다르면 409.
		Optional<DecisionResponse> replay = replayOrConflict(recordTx.findByKey(key.projectId(), idempotencyKey), fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}

		// 3. 가격 snapshot을 고르고 정책과 함께 이 요청에 묶는다. 같은 requestId의 context가 이미 있으면 그것을 재사용한다.
		LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
		PriceSelection selection = selectPrice(command.provider(), command.model(), nowUtc);
		ContextView context = contexts.capture(key, command.environment(), command.requestId(), selection.snapshotId(), fingerprint);

		// J. 같은 requestId에 이미 판정이 있다. 같은 키면 2 이후 동시에 저장된 것이므로 지문으로 판단하고, 다른 키면 409다.
		Optional<DecisionResponse> existing = existingForRequest(context.context().getId(), idempotencyKey, fingerprint);
		if (existing.isPresent()) {
			return existing.get();
		}
		// 같은 requestId를 다른 본문(예: 다른 모델)으로 보낸 요청이 먼저 context를 묶었고 아직 판정을 저장하지 않았다.
		// 그 가격 snapshot은 다른 모델의 것일 수 있으므로 평가하지 않고 409로 끝낸다.
		String boundFingerprint = context.context().getRequestFingerprint();
		if (boundFingerprint != null && !boundFingerprint.equals(fingerprint)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "requestId is already bound to another request body.");
		}

		// 4. 계산. 가격은 context에 묶인 snapshot을 쓴다(재사용된 context면 지금 고른 가격이 아니다).
		Instant now = clock.instant();
		PriceInput price = priceInput(context, selection);
		List<ScopeInput> scopes = scopeInputs(key, command.environment(), context.policies(), now);
		Evaluation evaluation = AdvisoryEvaluator.evaluate(new AdvisoryEvaluator.Input(
			command.estimatedInputTokens(), command.maxOutputTokens(), price, scopes, now));

		// 5. 저장. 이 시점에 바깥 트랜잭션(=projects 행 잠금)이 없어야 한다.
		String recordId = DecisionRecord.newId();
		DecisionResponse response = response(DecisionRecord.decisionIdOf(recordId), command, evaluation, context.policies(), now);
		DecisionRecord record = DecisionRecord.of(recordId, key.organizationId(), key.projectId(), key.apiKeyId(),
			context.context().getId(), idempotencyKey, fingerprint, command.clientMode(), evaluation.outcome(),
			evaluation.algorithmVersion(), LocalDateTime.ofInstant(now, ZoneOffset.UTC),
			write(evaluationInput(command, context, price, scopes, now)), write(response));

		requireNoOuterTransaction();
		try {
			recordTx.insert(record);
			return response;
		} catch (DataIntegrityViolationException exception) {
			return resolveRace(key.projectId(), idempotencyKey, fingerprint, context.context().getId(), exception);
		}
	}

	// 동시에 들어온 요청이 먼저 저장했다. 새 트랜잭션에서 다시 조회해 같은 판정이면 그것을, 아니면 409를 준다.
	private DecisionResponse resolveRace(String projectId, String idempotencyKey, String fingerprint, String contextId,
		DataIntegrityViolationException exception) {
		Optional<DecisionResponse> replay = replayOrConflict(recordTx.findByKey(projectId, idempotencyKey), fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}
		Optional<DecisionResponse> existing = existingForRequest(contextId, idempotencyKey, fingerprint);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (isUniqueViolation(exception)) {
			// 이긴 행을 찾지 못했다. 같은 요청을 다시 보내면 안전하다. 정책 거부로 바꾸지 않는다.
			log.warn("Decision unique violation without a matching row (project={})", projectId);
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		}
		throw exception;
	}

	private Optional<DecisionResponse> existingForRequest(String contextId, String idempotencyKey, String fingerprint) {
		Optional<DecisionRecord> existing = recordTx.findByContext(contextId);
		if (existing.isPresent() && !existing.get().getIdempotencyKey().equals(idempotencyKey)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "requestId already has a decision under another Idempotency-Key.");
		}
		return replayOrConflict(existing, fingerprint);
	}

	private static Optional<DecisionResponse> replayOrConflict(Optional<DecisionRecord> existing, String fingerprint) {
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return Optional.of(read(existing.get().getResponseJson()));
	}

	private PriceSelection selectPrice(String provider, String model, LocalDateTime nowUtc) {
		long unit = pricingProperties.rateUnitTokens();
		if (unit <= 0) {
			// 요율 단위를 확인하지 않았으면 snapshot을 만들 수 없다(가격 미상).
			return PriceSelection.unavailable("RATE_UNIT_UNCONFIRMED");
		}
		Optional<PricingPlan> plan = pricingPlans.findPricingPlans(provider, model, true, nowUtc).stream().findFirst();
		if (plan.isEmpty()) {
			return PriceSelection.unavailable("PRICING_NOT_FOUND");
		}
		if (!"USD".equals(plan.get().getCurrency())) {
			return PriceSelection.unavailable("CURRENCY_UNSUPPORTED");
		}
		try {
			PricingSnapshot snapshot = pricingSnapshots.capture(plan.get(), unit, SNAPSHOT_ACTOR_TYPE, SNAPSHOT_ACTOR_ID);
			return new PriceSelection(snapshot.getId(), null);
		} catch (ApiException exception) {
			if (exception.getErrorCode() == ErrorCode.IDEMPOTENCY_CONFLICT) {
				// 같은 가격표 버전에 다른 내용의 snapshot이 이미 있다. 어느 쪽이 맞는지 모르므로 가격 미상으로 둔다.
				return PriceSelection.unavailable("PRICE_SNAPSHOT_CONFLICT");
			}
			throw exception;
		}
	}

	private static PriceInput priceInput(ContextView context, PriceSelection selection) {
		if (context.price() == null) {
			// 묶인 가격이 없다. 사유는 지금 고른 결과에서 가져온다. 재사용된 context에 가격이 없는데 지금은 가격이 있으면
			// 묶인 snapshot을 바꾸지 않고 PRICE_NOT_BOUND(가격 미상)로 둔다.
			return PriceInput.unavailable(selection.unavailableCode() == null ? "PRICE_NOT_BOUND" : selection.unavailableCode());
		}
		try {
			JsonNode snapshot = JSON.readTree(context.price().snapshotJson());
			// pricing_snapshots.snapshot_json은 엔티티에 JSON 타입 지정이 없어, H2에서는 JSON 문자열 값(따옴표로 감싼 텍스트)으로 읽힌다.
			if (snapshot.isTextual()) {
				snapshot = JSON.readTree(snapshot.asText());
			}
			JsonNode rates = snapshot.path("rates");
			return PriceInput.available(new ServerPriceCalculator.Rates(rate(rates, "prompt"), rate(rates, "completion"),
				rate(rates, "cachedPrompt"), rate(rates, "reasoning")), snapshot.path("rateUnitTokens").asLong(0));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid stored pricing snapshot " + context.price().snapshotId(), exception);
		}
	}

	private static BigDecimal rate(JsonNode rates, String name) {
		JsonNode value = rates.path(name);
		return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
	}

	// 활성 정책의 범위만 월 지출을 조회한다. 프로젝트 범위는 모든 환경의 합계, 환경 범위는 그 환경의 합계다(UTC 월).
	private List<ScopeInput> scopeInputs(AuthenticatedProjectApiKey key, String environment, List<PolicyVersionView> policies,
		Instant now) {
		LocalDateTime from = monthStart(now);
		LocalDateTime to = from.plusMonths(1);
		List<ScopeInput> scopes = new ArrayList<>();
		for (PolicyVersionView policy : policies) {
			Scope scope = policy.environment() == null ? Scope.PROJECT : Scope.ENVIRONMENT;
			BigDecimal monthSpend = BigDecimal.ZERO;
			if (Boolean.TRUE.equals(policy.rules().active())) {
				BigDecimal sum = spend.sumConfirmedSpend(key.organizationId(), key.projectId(),
					scope == Scope.PROJECT ? null : environment, from, to,
					AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED);
				monthSpend = sum == null ? BigDecimal.ZERO : sum;
			}
			scopes.add(new ScopeInput(scope, policy.rules(), policy.version(), monthSpend));
		}
		return scopes;
	}

	private DecisionResponse response(String decisionId, DecisionCommand command, Evaluation evaluation,
		List<PolicyVersionView> policies, Instant now) {
		List<DecisionResponse.PolicyRef> refs = policies.stream()
			.map(p -> new DecisionResponse.PolicyRef(p.environment() == null ? "PROJECT" : "ENVIRONMENT", p.policyId(),
				p.version(), Boolean.TRUE.equals(p.rules().active())))
			.toList();
		return new DecisionResponse(
			SCHEMA_VERSION,
			decisionId,
			command.requestId(),
			evaluation.outcome(),
			effectiveMode(command.clientMode()),
			evaluation.reasons().stream().map(r -> new DecisionResponse.Reason(r.code(), r.message())).toList(),
			policyVersion(policies),
			refs,
			usd(evaluation.requiredCostUsd()),
			usd(evaluation.remainingMonthlyBudgetUsd()),
			evaluation.retryAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(evaluation.retryAt()),
			DateTimeFormatter.ISO_INSTANT.format(now)
		);
	}

	// #17 전까지 ENFORCE 요청도 Advisory로만 판정한다. 서버는 클라이언트 설정을 바꾸지 않는다.
	static EnforcementMode effectiveMode(EnforcementMode clientMode) {
		return clientMode == EnforcementMode.ENFORCE ? EnforcementMode.ADVISORY : clientMode;
	}

	// 범위별 prj:/env: 버전을 프로젝트·환경 순으로 '.'로 잇는다. 정책이 없으면 none.
	static String policyVersion(List<PolicyVersionView> policies) {
		if (policies.isEmpty()) {
			return "none";
		}
		List<String> parts = new ArrayList<>();
		for (PolicyVersionView policy : policies) {
			parts.add((policy.environment() == null ? "prj:" : "env:") + policy.version());
		}
		return String.join(".", parts);
	}

	// 재현에 필요한 입력만 남긴다. 요청 텍스트나 metadata는 넣지 않는다.
	private static Map<String, Object> evaluationInput(DecisionCommand command, ContextView context, PriceInput price,
		List<ScopeInput> scopes, Instant now) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("algorithmVersion", AdvisoryEvaluator.ALGORITHM_VERSION);
		input.put("evaluatedAt", DateTimeFormatter.ISO_INSTANT.format(now));
		input.put("monthWindowFrom", monthStart(now).toString());
		input.put("estimatedInputTokens", command.estimatedInputTokens());
		input.put("maxOutputTokens", command.maxOutputTokens());
		input.put("pricingSnapshotId", context.price() == null ? null : context.price().snapshotId());
		input.put("priceUnavailableCode", price.unavailableCode());
		List<Map<String, Object>> scopeRows = new ArrayList<>();
		for (ScopeInput scope : scopes) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("scope", scope.scope().name());
			row.put("policyVersion", scope.policyVersion());
			row.put("active", scope.rules() != null && Boolean.TRUE.equals(scope.rules().active()));
			row.put("monthSpendUsd", scope.monthSpendUsd().toPlainString());
			scopeRows.add(row);
		}
		input.put("scopes", scopeRows);
		return input;
	}

	private static LocalDateTime monthStart(Instant now) {
		return LocalDateTime.ofInstant(now, ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay();
	}

	private static String usd(BigDecimal amount) {
		return amount == null ? null : amount.setScale(6).toPlainString();
	}

	private static void requireNoOuterTransaction() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("DecisionService must not run inside a transaction: "
				+ "an outer transaction could still hold the projects row lock taken by capture.");
		}
	}

	private static String write(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Cannot serialize decision record", exception);
		}
	}

	private static DecisionResponse read(String json) {
		try {
			return JSON.readValue(json, DecisionResponse.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid stored decision response", exception);
		}
	}

	private boolean isUniqueViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
				&& violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
				return true;
			}
			if (cause instanceof SQLException sql && (UNIQUE_SQL_STATE.equals(sql.getSQLState())
				|| sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY)) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	private record PriceSelection(String snapshotId, String unavailableCode) {
		static PriceSelection unavailable(String code) {
			return new PriceSelection(null, code);
		}
	}
}
