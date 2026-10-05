package com.tokenledgercloud.api.domain.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tokenledgercloud.api.domain.ingestion.service.PayloadFingerprint;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateResult;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class UsageLogServiceIdempotencyTest {

	private static final String PROJECT = "project-1";
	private static final String ENV = "prod";

	@Mock
	private UsageLogRepository usageLogRepository;

	@InjectMocks
	private UsageLogService usageLogService;

	@BeforeEach
	void stubSave() {
		org.mockito.Mockito.lenient().when(usageLogRepository.save(any(UsageLog.class))).thenAnswer(invocation -> {
			UsageLog log = invocation.getArgument(0);
			log.setId("usage-new");
			log.setCreatedAt(LocalDateTime.of(2026, 10, 5, 0, 0));
			return log;
		});
	}

	private static UsageLogCreateRequest request(String requestId, String eventId, long promptTokens) {
		return new UsageLogCreateRequest(
			"org-1", PROJECT, "key-1", ENV, requestId,
			"openai", "gpt-4o-mini",
			promptTokens, 400L, 0L, 0L, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", "sdk", null,
			LocalDateTime.of(2026, 5, 6, 10, 0),
			eventId
		);
	}

	private static UsageLog stored(UsageLogCreateRequest request, String fingerprint) {
		return UsageLog.builder()
			.id("usage-existing")
			.organizationId(request.organizationId())
			.projectId(request.projectId())
			.environment(request.environment())
			.requestId(request.requestId())
			.eventId(request.eventId())
			.payloadFingerprint(fingerprint)
			.provider(request.provider())
			.model(request.model())
			.promptTokens(request.promptTokens())
			.completionTokens(request.completionTokens())
			.reasoningTokens(0L)
			.cachedPromptTokens(0L)
			.totalTokens(1600L)
			.promptCostUsd(request.promptCostUsd())
			.completionCostUsd(request.completionCostUsd())
			.reasoningCostUsd(BigDecimal.ZERO)
			.cachedPromptCostUsd(BigDecimal.ZERO)
			.totalCostUsd(new BigDecimal("0.00042"))
			.pricingVersion(request.pricingVersion())
			.sourceType("sdk")
			.occurredAt(request.occurredAt())
			.createdAt(LocalDateTime.of(2026, 5, 6, 10, 0, 1))
			.build();
	}

	private void givenStoredByRequestId(UsageLog log) {
		given(usageLogRepository.findByProjectIdAndEnvironmentAndRequestId(PROJECT, ENV, log.getRequestId()))
			.willReturn(Optional.of(log));
	}

	private void givenStoredByEventId(UsageLog log) {
		given(usageLogRepository.findByProjectIdAndEnvironmentAndEventId(PROJECT, ENV, log.getEventId()))
			.willReturn(Optional.of(log));
	}

	@Test
	void newEventIsSavedWithFingerprintAndEventId() {
		UsageLogCreateRequest request = request("req-1", "evt-1", 1200L);

		UsageLogCreateResult result = usageLogService.createIdempotent(request);

		assertThat(result.duplicate()).isFalse();
		assertThat(result.log().id()).isEqualTo("usage-new");
		ArgumentCaptor<UsageLog> saved = ArgumentCaptor.forClass(UsageLog.class);
		verify(usageLogRepository).save(saved.capture());
		assertThat(saved.getValue().getEventId()).isEqualTo("evt-1");
		assertThat(saved.getValue().getPayloadFingerprint()).isEqualTo(PayloadFingerprint.of(request));
	}

	@Test
	void eventWithoutEventIdFallsBackToRequestIdKey() {
		UsageLogCreateRequest request = request("req-1", null, 1200L);
		givenStoredByRequestId(stored(request, PayloadFingerprint.of(request)));

		UsageLogCreateResult result = usageLogService.createIdempotent(request);

		assertThat(result.duplicate()).isTrue();
		assertThat(result.log().id()).isEqualTo("usage-existing");
		verify(usageLogRepository, never()).save(any());
	}

	@Test
	void blankEventIdIsTreatedAsAbsent() {
		UsageLogCreateRequest request = request("req-1", "  ", 1200L);
		givenStoredByRequestId(stored(request(request.requestId(), null, 1200L), PayloadFingerprint.of(request)));

		assertThat(usageLogService.createIdempotent(request).duplicate()).isTrue();
		verify(usageLogRepository, never()).findByProjectIdAndEnvironmentAndEventId(any(), any(), any());
	}

	@Test
	void samePayloadWithSameEventIdReturnsStoredEvent() {
		UsageLogCreateRequest request = request("req-1", "evt-1", 1200L);
		givenStoredByEventId(stored(request, PayloadFingerprint.of(request)));

		UsageLogCreateResult result = usageLogService.createIdempotent(request);

		assertThat(result.duplicate()).isTrue();
		assertThat(result.log().id()).isEqualTo("usage-existing");
		verify(usageLogRepository, never()).save(any());
	}

	@Test
	void differentPayloadWithSameEventIdIsConflict() {
		UsageLogCreateRequest original = request("req-1", "evt-1", 1200L);
		givenStoredByEventId(stored(original, PayloadFingerprint.of(original)));

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", "evt-1", 9999L)))
			.isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).getErrorCode())
			.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
		verify(usageLogRepository, never()).save(any());
	}

	@Test
	void differentPayloadWithSameRequestIdIsConflict() {
		UsageLogCreateRequest original = request("req-1", null, 1200L);
		givenStoredByRequestId(stored(original, PayloadFingerprint.of(original)));

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", null, 9999L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode().getCode()).isEqualTo("INGESTION-409"));
	}

	@Test
	void sameEventIdWithDifferentRequestIdIsConflictEvenWhenPayloadMatches() {
		UsageLogCreateRequest original = request("req-1", "evt-1", 1200L);
		givenStoredByEventId(stored(original, PayloadFingerprint.of(original)));

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-2", "evt-1", 1200L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void newEventIdReusingStoredRequestIdIsConflict() {
		UsageLogCreateRequest original = request("req-1", "evt-1", 1200L);
		givenStoredByRequestId(stored(original, PayloadFingerprint.of(original)));

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", "evt-2", 1200L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		verify(usageLogRepository, never()).save(any());
	}

	@Test
	void legacyRowWithoutFingerprintIsReturnedAsDuplicate() {
		UsageLogCreateRequest original = request("req-1", null, 1200L);
		givenStoredByRequestId(stored(original, null));

		UsageLogCreateResult result = usageLogService.createIdempotent(request("req-1", null, 9999L));

		assertThat(result.duplicate()).isTrue();
		assertThat(result.log().id()).isEqualTo("usage-existing");
	}

	@Test
	void requestWithoutAnyKeyIsAlwaysSaved() {
		UsageLogCreateResult result = usageLogService.createIdempotent(request(null, null, 1200L));

		assertThat(result.duplicate()).isFalse();
		verify(usageLogRepository, never()).findByProjectIdAndEnvironmentAndRequestId(any(), any(), any());
	}

	@Test
	void invalidMetadataJsonIsRejectedAsInvalidInput() {
		UsageLogCreateRequest b = request("req-1", null, 1200L);
		UsageLogCreateRequest broken = new UsageLogCreateRequest(
			b.organizationId(), b.projectId(), b.apiKeyId(), b.environment(), b.requestId(), b.provider(), b.model(),
			b.promptTokens(), b.completionTokens(), b.reasoningTokens(), b.cachedPromptTokens(), b.totalTokens(),
			b.promptCostUsd(), b.completionCostUsd(), b.reasoningCostUsd(), b.cachedPromptCostUsd(), b.totalCostUsd(),
			b.pricingPlanId(), b.pricingVersion(), b.sourceType(), "{not json", b.occurredAt(), null);

		assertThatThrownBy(() -> usageLogService.createIdempotent(broken))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
	}

	@Test
	void legacyCreateKeepsReturningExistingRowWithoutComparingPayloads() {
		UsageLogCreateRequest original = request("req-1", null, 1200L);
		givenStoredByRequestId(stored(original, PayloadFingerprint.of(original)));

		assertThat(usageLogService.create(request("req-1", null, 9999L)).id()).isEqualTo("usage-existing");
		verify(usageLogRepository, never()).save(any());
	}
}
