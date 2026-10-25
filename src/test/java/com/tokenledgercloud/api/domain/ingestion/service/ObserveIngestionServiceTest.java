package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshotRepository;
import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * Observe 수집(v2) 전체 흐름을 확인한다: 가격 계산, 스냅샷, 원장 기록, 중복 방지.
 * 요율 단위가 확인된(100만 토큰당) 설정으로 돌린다. API 키 검증만 가짜로 대체한다.
 * 수집이 REQUIRES_NEW로 커밋하므로 @Transactional을 붙이지 않고, 테스트마다 서로 다른 eventId를 쓴다.
 */
// 이 테스트 전용 인메모리 DB를 만들고, 요율 단위를 100만 토큰당으로 설정한다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ingestion;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.observe.ingestion.rate-unit-tokens=1000000"
})
class ObserveIngestionServiceTest {

	// 가격표가 있는 모델 이름과 없는 모델 이름.
	private static final String PRICED_MODEL = "model-priced";
	private static final String UNKNOWN_MODEL = "model-without-plan";

	@Autowired ObserveIngestionService service;
	@Autowired UsageLogRepository usageLogRepository;
	@Autowired UsageAccountingValueRepository valueRepository;
	@Autowired PricingPlanRepository pricingPlanRepository;
	@Autowired PricingSnapshotRepository snapshotRepository;

	// API 키 검증은 가짜로 대체한다. 이 테스트의 관심사는 가격 계산과 원장 기록이다.
	@MockitoBean ProjectApiKeyAuthenticator authenticator;

	@BeforeEach
	void stubAuthenticationAndSeedPlan() {
		// 어떤 키로 와도 같은 프로젝트로 인증된 것으로 만든다.
		when(authenticator.authenticate(any(), any(), any()))
				.thenReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		// 가격표를 한 번만 만든다. (롤백하지 않는 테스트라 중복 저장을 피한다)
		if (pricingPlanRepository.findPricingPlans("openai", PRICED_MODEL, false, LocalDateTime.now()).isEmpty()) {
			pricingPlanRepository.saveAndFlush(plan("plan-observe-1", PRICED_MODEL, "observe-v1-prices", "2.000000"));
		}
	}

	// 테스트용 가격표. 프롬프트 요율만 바꿔 쓸 수 있게 한다.
	private static PricingPlan plan(String id, String model, String version, String promptRate) {
		return plan(id, model, version, promptRate, LocalDateTime.of(2026, 1, 1, 0, 0));
	}

	private static PricingPlan plan(String id, String model, String version, String promptRate,
			LocalDateTime effectiveFrom) {
		return PricingPlan.builder()
				.id(id)
				.catalogId("catalog-1")
				.provider("openai")
				.model(model)
				.currency("USD")
				.promptRate(new BigDecimal(promptRate))
				.completionRate(new BigDecimal("8.000000"))
				.cachedPromptRate(new BigDecimal("0.500000"))
				.reasoningRate(new BigDecimal("8.000000"))
				.version(version)
				.effectiveFrom(effectiveFrom)
				.build();
	}

	// 요청을 만드는 도우미. 이벤트 id, 모델, 호출 결과, 토큰만 바꿔서 쓴다.
	private static ObserveEventRequest request(String eventId, String model, CallStatus callStatus,
			Long prompt, Long completion, Long cached, Long reasoning) {
		return new ObserveEventRequest("project-key", "prod", eventId, "req-" + eventId, "observe-v1",
				TokenSemantics.INCLUSIVE_V1, "openai", model, callStatus,
				prompt, completion, cached, reasoning, OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.UTC), null);
	}

	// 이벤트에 연결된 원장 행 목록을 읽는 도우미.
	private List<UsageAccountingValue> valuesOf(String usageEventId) {
		return valueRepository.findAll().stream()
				.filter(value -> value.getUsageEventId().equals(usageEventId))
				.toList();
	}

	@Test
	void pricedEventStoresSnapshotAndPricedActualAmount() {
		// 설계 문서의 예와 같은 토큰으로 수집한다.
		var response = service.collect("api-key",
				request("obs-priced", PRICED_MODEL, CallStatus.SUCCEEDED, 1200L, 400L, 300L, 100L));
		assertThat(response.duplicate()).isFalse();
		// 원장에는 0.00515달러가 확정(PRICED)으로 한 줄 기록된다.
		List<UsageAccountingValue> values = valuesOf(response.usageEventId());
		assertThat(values).hasSize(1);
		assertThat(values.get(0).getAmount()).isEqualByComparingTo("0.005150");
		assertThat(values.get(0).getPricingSnapshotId()).isNotNull();
		// 이벤트는 정산 완료이고 새 계약 값으로 저장된다.
		UsageLog event = usageLogRepository.findById(response.usageEventId()).orElseThrow();
		assertThat(event.getSettlementState()).isEqualTo("SETTLED");
		assertThat(event.getSchemaVersion()).isEqualTo("observe-v1");
		assertThat(event.getTokenSemantics()).isEqualTo("INCLUSIVE_V1");
		// 설계 문서 §4: 프로젝트 키로 쓴 행의 기록 주체는 PROJECT_API_KEY/키 id다.
		assertThat(event.getActorType()).isEqualTo("PROJECT_API_KEY");
		assertThat(event.getActorId()).isEqualTo("key-1");
		assertThat(event.getTotalTokens()).isEqualTo(1600L);
		// 계산에 쓴 가격표가 스냅샷으로 남는다.
		assertThat(snapshotRepository.findByProviderAndModelAndCurrencyAndVersion(
				"openai", PRICED_MODEL, "USD", "observe-v1-prices")).isPresent();
	}

	@Test
	void modelWithoutPricePlanIsUnpricedNotZero() {
		// 가격표가 없는 모델로 수집한다.
		var response = service.collect("api-key",
				request("obs-noplan", UNKNOWN_MODEL, CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		// 금액은 0이 아니라 null이고, 사유가 남으며, 정산은 UNSETTLED다.
		List<UsageAccountingValue> values = valuesOf(response.usageEventId());
		assertThat(values).hasSize(1);
		assertThat(values.get(0).getAmount()).isNull();
		assertThat(values.get(0).getReasonCode()).isEqualTo("PRICE_NOT_FOUND");
		UsageLog event = usageLogRepository.findById(response.usageEventId()).orElseThrow();
		assertThat(event.getSettlementState()).isEqualTo("UNSETTLED");
	}

	@Test
	void sameEventSentTwiceIsRecordedOnce() {
		var first = service.collect("api-key",
				request("obs-dup", PRICED_MODEL, CallStatus.SUCCEEDED, 1200L, 400L, 300L, 100L));
		var second = service.collect("api-key",
				request("obs-dup", PRICED_MODEL, CallStatus.SUCCEEDED, 1200L, 400L, 300L, 100L));
		// 두 번째는 중복으로 판정되고, 원장 행은 한 줄뿐이다. (비용이 두 번 오르지 않는다)
		assertThat(first.duplicate()).isFalse();
		assertThat(second.duplicate()).isTrue();
		assertThat(second.usageEventId()).isEqualTo(first.usageEventId());
		assertThat(valuesOf(first.usageEventId())).hasSize(1);
	}

	@Test
	void sameEventIdWithDifferentTokensIsRejected() {
		service.collect("api-key", request("obs-conflict", PRICED_MODEL, CallStatus.SUCCEEDED, 1200L, 400L, 300L, 100L));
		// 같은 eventId에 다른 토큰이 오면 충돌로 거절된다.
		assertThatThrownBy(() -> service.collect("api-key",
				request("obs-conflict", PRICED_MODEL, CallStatus.SUCCEEDED, 9999L, 400L, 300L, 100L)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void sameInstantWithDifferentOffsetIsTheSameEvent() {
		var first = service.collect("api-key",
				request("obs-offset", PRICED_MODEL, CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		ObserveEventRequest original = request("obs-offset", PRICED_MODEL, CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L);
		// 2026-10-10T12:00Z와 같은 순간을 서울 시각으로 보낸다.
		var second = service.collect("api-key", new ObserveEventRequest(original.projectKey(), original.environment(),
				original.eventId(), original.requestId(), original.schemaVersion(), original.tokenSemantics(),
				original.provider(), original.model(), original.callStatus(), original.promptTokens(),
				original.completionTokens(), original.cachedPromptTokens(), original.reasoningTokens(),
				OffsetDateTime.of(2026, 10, 10, 21, 0, 0, 0, ZoneOffset.ofHours(9)), original.metadata()));
		assertThat(second.duplicate()).isTrue();
		assertThat(second.usageEventId()).isEqualTo(first.usageEventId());
		assertThat(usageLogRepository.findById(first.usageEventId()).orElseThrow().getOccurredAt())
				.isEqualTo(LocalDateTime.of(2026, 10, 10, 12, 0));
	}

	@Test
	void keyWithoutEnvironmentRestrictionRecordsTheRequestedEnvironment() {
		// 환경 제한이 없는 키는 인증 결과의 환경이 비어 있다.
		when(authenticator.authenticate(any(), any(), any()))
				.thenReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-any-env", null));
		var response = service.collect("api-key",
				request("obs-any-env", PRICED_MODEL, CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		assertThat(usageLogRepository.findById(response.usageEventId()).orElseThrow().getEnvironment())
				.isEqualTo("prod");
	}

	@Test
	void resendAfterPricePlanChangedIsStillADuplicate() {
		pricingPlanRepository.saveAndFlush(plan("plan-repriced", "model-repriced", "reprice-v1", "2.000000"));
		var first = service.collect("api-key",
				request("obs-reprice", "model-repriced", CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		// 같은 버전으로 요율이 다른 가격표가 나중에 유효해진다. 다시 계산하면 저장된 스냅샷과 충돌하는 상태다.
		pricingPlanRepository.saveAndFlush(plan("plan-repriced-2", "model-repriced", "reprice-v1", "3.000000",
				LocalDateTime.of(2026, 6, 1, 0, 0)));
		// 재전송은 가격을 다시 매기지 않으므로 충돌 없이 처음 기록을 돌려받는다.
		var second = service.collect("api-key",
				request("obs-reprice", "model-repriced", CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		assertThat(second.duplicate()).isTrue();
		assertThat(second.usageEventId()).isEqualTo(first.usageEventId());
	}

	@Test
	void snapshotConflictRecordsTheEventAsUnpricedInsteadOfRejectingIt() {
		PricingPlan plan = pricingPlanRepository.saveAndFlush(
				plan("plan-snapshot-conflict", "model-snapshot-conflict", "conflict-v1", "2.000000"));
		// 같은 버전으로 다른 요율 단위의 스냅샷이 이미 있다. (예: rate-unit-tokens를 잘못 넣고 수집했던 경우)
		snapshotRepository.saveAndFlush(PricingSnapshot.fromPlan(plan, 1000L, "SYSTEM", "test"));
		var response = service.collect("api-key",
				request("obs-snapshot-conflict", "model-snapshot-conflict", CallStatus.SUCCEEDED, 100L, 50L, 0L, 0L));
		assertThat(response.duplicate()).isFalse();
		List<UsageAccountingValue> values = valuesOf(response.usageEventId());
		assertThat(values).hasSize(1);
		assertThat(values.get(0).getAmount()).isNull();
		assertThat(values.get(0).getReasonCode()).isEqualTo("PRICE_SNAPSHOT_CONFLICT");
	}

	@Test
	void unknownUsageCreatesEventWithoutLedgerValue() {
		// 사용량을 알 수 없는 호출은 토큰 없이 수집된다.
		var response = service.collect("api-key",
				request("obs-unknown", PRICED_MODEL, CallStatus.USAGE_UNKNOWN, null, null, null, null));
		UsageLog event = usageLogRepository.findById(response.usageEventId()).orElseThrow();
		assertThat(event.getCallStatus()).isEqualTo("USAGE_UNKNOWN");
		// 금액을 0으로 만들지 않으므로 원장 값은 없다.
		assertThat(valuesOf(response.usageEventId())).isEmpty();
	}

	@Test
	void invalidTokensAreRejected() {
		// 캐시 토큰이 프롬프트 토큰보다 크면 포함 관계가 틀린 것이므로 거절한다.
		assertThatThrownBy(() -> service.collect("api-key",
				request("obs-bad1", PRICED_MODEL, CallStatus.SUCCEEDED, 100L, 50L, 200L, 0L)))
				.isInstanceOf(ApiException.class);
		// 성공한 호출인데 토큰이 빠져 있어도 거절한다.
		assertThatThrownBy(() -> service.collect("api-key",
				request("obs-bad2", PRICED_MODEL, CallStatus.SUCCEEDED, null, null, null, null)))
				.isInstanceOf(ApiException.class);
	}
}
