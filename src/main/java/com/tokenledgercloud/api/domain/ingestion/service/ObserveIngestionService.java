package com.tokenledgercloud.api.domain.ingestion.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.ServerPriceCalculator;
import com.tokenledgercloud.api.domain.accounting.UsageTokens;
import com.tokenledgercloud.api.domain.accounting.ledger.AccountingLedgerCommand;
import com.tokenledgercloud.api.domain.accounting.ledger.AccountingLedgerResult;
import com.tokenledgercloud.api.domain.accounting.ledger.AccountingLedgerWriter;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshotService;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventResponse;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Observe 사용 이벤트(v2)를 받아 가격을 계산하고 원장에 한 번만 기록한다.
 * 가격을 확정할 수 없으면 0달러로 기록하지 않고 "가격 미상(UNPRICED)"으로 기록한다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ObserveIngestionService {

	// 원장에 남기는 기록 주체. 감사 주체는 클라이언트 주장이 아니라 서버가 정한다.
	private static final String ACTOR_TYPE = "SYSTEM";

	// 프로젝트 API 키를 검증하는 빈. 기존 수집 API와 같은 것을 쓴다.
	private final ProjectApiKeyAuthenticator apiKeyAuthenticator;
	// 호출 시점에 유효한 가격표를 찾는 저장소.
	private final PricingPlanRepository pricingPlanRepository;
	// 가격표를 불변 스냅샷으로 남기는 서비스.
	private final PricingSnapshotService pricingSnapshotService;
	// 이벤트를 원장에 한 번만 쌓는 writer.
	private final AccountingLedgerWriter ledgerWriter;

	// 가격표 요율이 몇 토큰당인지. 확인한 값을 설정으로 넣어야 한다. 0이면 확인 전이라 모든 이벤트를 가격 미상으로 기록한다.
	@Value("${token-pilot.observe.ingestion.rate-unit-tokens:0}")
	private long rateUnitTokens;

	/** 이벤트를 수집한다. */
	public ObserveEventResponse collect(String apiKey, ObserveEventRequest request) {
		// 1. API 키로 프로젝트와 환경을 확인한다. 키가 없거나 틀리면 여기서 거절된다.
		AuthenticatedProjectApiKey auth = apiKeyAuthenticator.authenticate(
				apiKey, request.projectKey(), request.environment());

		// 2. 호출이 성공했을 때만 토큰을 검증하고 비용을 계산한다. 그 밖의 상태는 금액을 만들지 않는다.
		UsageTokens tokens = null;
		Pricing pricing = Pricing.none();
		if (request.callStatus() == CallStatus.SUCCEEDED) {
			tokens = tokensOf(request);
			pricing = price(tokens, request);
		}

		// 3. 원장 기록 명령을 만든다. 잘못된 값이면 DB에 닿기 전에 입력 오류로 거절한다.
		AccountingLedgerCommand command;
		try {
			command = new AccountingLedgerCommand(
					auth.organizationId(), auth.projectId(), auth.apiKeyId(), auth.environment(),
					request.requestId(), request.eventId(), ObserveEventFingerprint.of(auth, request),
					request.schemaVersion(), request.tokenSemantics(),
					request.provider(), request.model(),
					tokens == null ? 0 : tokens.promptTokens(),
					tokens == null ? 0 : tokens.completionTokens(),
					// 모르는 상세 토큰은 저장 컬럼이 NOT NULL이라 0으로 저장한다. (원문의 null은 가격 계산에만 쓰였다)
					tokens == null || tokens.reasoningTokens() == null ? 0 : tokens.reasoningTokens(),
					tokens == null || tokens.cachedPromptTokens() == null ? 0 : tokens.cachedPromptTokens(),
					tokens == null ? 0 : tokens.totalTokens(),
					request.occurredAt(), request.callStatus(),
					pricing.amount(), pricing.snapshotId(), null, pricing.reasonCode(),
					ACTOR_TYPE, "api-key:" + auth.apiKeyId());
		} catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, exception.getMessage());
		}

		// 4. 원장에 기록한다. 같은 이벤트가 다시 오면 새로 쌓지 않는다.
		AccountingLedgerResult result = ledgerWriter.record(command);
		return new ObserveEventResponse(request.eventId(), result.usageEventId(), result.duplicate());
	}

	// 성공한 호출의 토큰을 검증한다. 포함 관계가 틀리거나 값이 빠졌으면 입력 오류로 거절한다.
	private UsageTokens tokensOf(ObserveEventRequest request) {
		if (request.promptTokens() == null || request.completionTokens() == null) {
			throw new ApiException(ErrorCode.INVALID_INPUT,
					"promptTokens and completionTokens are required when callStatus is SUCCEEDED");
		}
		try {
			return new UsageTokens(request.promptTokens(), request.completionTokens(),
					request.cachedPromptTokens(), request.reasoningTokens());
		} catch (IllegalArgumentException | ArithmeticException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, exception.getMessage());
		}
	}

	// 호출 시점에 유효한 가격표로 비용을 계산한다. 확신할 수 없으면 가격 미상으로 돌려준다.
	private Pricing price(UsageTokens tokens, ObserveEventRequest request) {
		// 요율 단위를 아직 확인하지 않았으면 계산하지 않는다.
		if (rateUnitTokens <= 0) {
			return Pricing.unpriced("RATE_UNIT_UNCONFIRMED");
		}
		// 호출이 발생한 시각에 유효했던 가장 최신 가격표를 찾는다.
		Optional<PricingPlan> found = pricingPlanRepository
				.findPricingPlans(request.provider(), request.model(), true, request.occurredAt())
				.stream().findFirst();
		if (found.isEmpty()) {
			return Pricing.unpriced("PRICE_NOT_FOUND");
		}
		PricingPlan plan = found.get();
		// 통화는 USD만 지원한다.
		if (!"USD".equals(plan.getCurrency())) {
			return Pricing.unpriced("CURRENCY_UNSUPPORTED");
		}
		// 설계 문서의 규칙으로 비용을 계산한다.
		ServerPriceCalculator.Result result = ServerPriceCalculator.calculate(tokens,
				new ServerPriceCalculator.Rates(plan.getPromptRate(), plan.getCompletionRate(),
						plan.getCachedPromptRate(), plan.getReasoningRate()),
				rateUnitTokens);
		if (!result.isPriced()) {
			return Pricing.unpriced(result.reasonCode());
		}
		// 금액이 허용 범위를 벗어나면 0이나 잘린 값이 아니라 가격 미상으로 남긴다.
		AccountingAmount amount;
		try {
			amount = new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED,
					result.amount(), "USD", AccountingAmount.Source.SERVER_CATALOG);
		} catch (IllegalArgumentException exception) {
			return Pricing.unpriced("AMOUNT_OUT_OF_RANGE");
		}
		// 계산에 쓴 가격표를 불변 스냅샷으로 남긴다. 같은 버전에 다른 내용이면 충돌로 거절된다.
		PricingSnapshot snapshot = pricingSnapshotService.capture(plan, rateUnitTokens, ACTOR_TYPE, "observe-ingestion");
		return new Pricing(amount, snapshot.getId(), result.reasonCode());
	}

	// 가격 계산 결과를 묶는 내부 값.
	private record Pricing(AccountingAmount amount, String snapshotId, String reasonCode) {

		// 금액을 만들지 않는 경우(성공이 아닌 호출).
		static Pricing none() {
			return new Pricing(null, null, "NO_USAGE_REPORTED");
		}

		// 가격 미상으로 기록하는 경우. 금액은 0이 아니라 null이다.
		static Pricing unpriced(String reasonCode) {
			return new Pricing(new AccountingAmount(AccountingAmount.Kind.ACTUAL,
					AccountingAmount.PricingStatus.UNPRICED, null, "USD", AccountingAmount.Source.NONE),
					null, reasonCode);
		}
	}
}
