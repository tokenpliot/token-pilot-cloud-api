package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;

class PayloadFingerprintTest {

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 2, 12, 0, 2);

	private static UsageLogCreateRequest base() {
		return new UsageLogCreateRequest(
			"org-1", "project-1", "key-1", "prod", "req-1",
			"openai", "gpt-4o-mini",
			1180L, 640L, 0L, 0L, 1820L,
			new BigDecimal("0.000177"), new BigDecimal("0.000384"),
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.000561"),
			null, "openai-2026-08-14", "sdk",
			"{\"feature\":\"summary\",\"team\":\"a\"}",
			OCCURRED_AT
		);
	}

	private static UsageLogCreateRequest copy(
		UsageLogCreateRequest r,
		Long promptTokens, Long reasoningTokens, Long cachedPromptTokens, Long totalTokens,
		BigDecimal promptCost, BigDecimal reasoningCost, BigDecimal totalCost,
		String sourceType, String metadataJson, LocalDateTime occurredAt
	) {
		return new UsageLogCreateRequest(
			r.organizationId(), r.projectId(), r.apiKeyId(), r.environment(), r.requestId(),
			r.provider(), r.model(),
			promptTokens, r.completionTokens(), reasoningTokens, cachedPromptTokens, totalTokens,
			promptCost, r.completionCostUsd(), reasoningCost, r.cachedPromptCostUsd(), totalCost,
			r.pricingPlanId(), r.pricingVersion(), sourceType, metadataJson, occurredAt
		);
	}

	@Test
	void producesLowercaseSha256Hex() {
		assertThat(PayloadFingerprint.of(base())).matches("[0-9a-f]{64}");
	}

	@Test
	void isDeterministic() {
		assertThat(PayloadFingerprint.of(base())).isEqualTo(PayloadFingerprint.of(base()));
	}

	@Test
	void ignoresKeyAndAuthScopeFields() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest other = new UsageLogCreateRequest(
			"org-2", "project-2", "key-2", "staging", "req-2",
			b.provider(), b.model(), b.promptTokens(), b.completionTokens(), b.reasoningTokens(),
			b.cachedPromptTokens(), b.totalTokens(), b.promptCostUsd(), b.completionCostUsd(),
			b.reasoningCostUsd(), b.cachedPromptCostUsd(), b.totalCostUsd(), b.pricingPlanId(),
			b.pricingVersion(), b.sourceType(), b.metadataJson(), b.occurredAt()
		);

		assertThat(PayloadFingerprint.of(other)).isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void metadataKeyOrderDoesNotMatter() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest reordered = copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			"{\"team\":\"a\",\"feature\":\"summary\"}", b.occurredAt());

		assertThat(PayloadFingerprint.of(reordered)).isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void nestedMetadataKeyOrderDoesNotMatter() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest one = copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			"{\"a\":{\"x\":1,\"y\":2}}", b.occurredAt());
		UsageLogCreateRequest two = copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			"{\"a\":{\"y\":2,\"x\":1}}", b.occurredAt());

		assertThat(PayloadFingerprint.of(one)).isEqualTo(PayloadFingerprint.of(two));
	}

	@Test
	void nullBlankAndEmptyMetadataAreEquivalent() {
		UsageLogCreateRequest b = base();
		String withNull = fingerprintWithMetadata(b, null);

		assertThat(fingerprintWithMetadata(b, "")).isEqualTo(withNull);
		assertThat(fingerprintWithMetadata(b, "  ")).isEqualTo(withNull);
		assertThat(fingerprintWithMetadata(b, "{}")).isEqualTo(withNull);
		assertThat(withNull).isNotEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void bigDecimalScaleDoesNotMatter() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest scaled = copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), new BigDecimal("0.0001770"), new BigDecimal("0.000000"), b.totalCostUsd(),
			b.sourceType(), b.metadataJson(), b.occurredAt());

		assertThat(PayloadFingerprint.of(scaled)).isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void omittedOptionalValuesEqualTheirStoredDefaults() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest omitted = copy(b, b.promptTokens(), null, null, null,
			b.promptCostUsd(), null, null, null, b.metadataJson(), b.occurredAt());

		assertThat(PayloadFingerprint.of(omitted)).isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void blankSourceTypeEqualsSdk() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest blank = copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), " ", b.metadataJson(),
			b.occurredAt());

		assertThat(PayloadFingerprint.of(blank)).isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void sameInstantInDifferentOffsetsHashesEqually() {
		OffsetDateTime seoul = OffsetDateTime.parse("2026-10-02T21:00:02+09:00");
		OffsetDateTime utc = OffsetDateTime.parse("2026-10-02T12:00:02Z");
		LocalDateTime fromSeoul = seoul.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		LocalDateTime fromUtc = utc.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		UsageLogCreateRequest b = base();

		assertThat(PayloadFingerprint.of(withOccurredAt(b, fromSeoul)))
			.isEqualTo(PayloadFingerprint.of(withOccurredAt(b, fromUtc)))
			.isEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void subMicrosecondDifferenceIsIgnoredToMatchStoragePrecision() {
		UsageLogCreateRequest b = base();

		assertThat(PayloadFingerprint.of(withOccurredAt(b, OCCURRED_AT.plusNanos(500))))
			.isEqualTo(PayloadFingerprint.of(b));
		assertThat(PayloadFingerprint.of(withOccurredAt(b, OCCURRED_AT.plusNanos(1_000))))
			.isNotEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void changedValuesProduceDifferentHashes() {
		UsageLogCreateRequest b = base();
		String original = PayloadFingerprint.of(b);

		assertThat(PayloadFingerprint.of(copy(b, 1181L, b.reasoningTokens(), b.cachedPromptTokens(), b.totalTokens(),
			b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(), b.metadataJson(), b.occurredAt())))
			.isNotEqualTo(original);
		assertThat(PayloadFingerprint.of(copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), new BigDecimal("0.000178"), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			b.metadataJson(), b.occurredAt())))
			.isNotEqualTo(original);
		assertThat(PayloadFingerprint.of(copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), "batch", b.metadataJson(),
			b.occurredAt())))
			.isNotEqualTo(original);
		assertThat(PayloadFingerprint.of(copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			"{\"feature\":\"summary\",\"team\":\"b\"}", b.occurredAt())))
			.isNotEqualTo(original);
	}

	@Test
	void differentProviderOrModelProducesDifferentHash() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest otherModel = new UsageLogCreateRequest(
			b.organizationId(), b.projectId(), b.apiKeyId(), b.environment(), b.requestId(),
			b.provider(), "gpt-4o", b.promptTokens(), b.completionTokens(), b.reasoningTokens(),
			b.cachedPromptTokens(), b.totalTokens(), b.promptCostUsd(), b.completionCostUsd(),
			b.reasoningCostUsd(), b.cachedPromptCostUsd(), b.totalCostUsd(), b.pricingPlanId(),
			b.pricingVersion(), b.sourceType(), b.metadataJson(), b.occurredAt()
		);

		assertThat(PayloadFingerprint.of(otherModel)).isNotEqualTo(PayloadFingerprint.of(b));
	}

	@Test
	void fieldValuesCannotCollideAcrossFieldBoundaries() {
		UsageLogCreateRequest b = base();
		UsageLogCreateRequest left = new UsageLogCreateRequest(
			b.organizationId(), b.projectId(), b.apiKeyId(), b.environment(), b.requestId(),
			"ab", "c", b.promptTokens(), b.completionTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.completionCostUsd(), b.reasoningCostUsd(),
			b.cachedPromptCostUsd(), b.totalCostUsd(), b.pricingPlanId(), b.pricingVersion(), b.sourceType(),
			b.metadataJson(), b.occurredAt()
		);
		UsageLogCreateRequest right = new UsageLogCreateRequest(
			b.organizationId(), b.projectId(), b.apiKeyId(), b.environment(), b.requestId(),
			"a", "bc", b.promptTokens(), b.completionTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.completionCostUsd(), b.reasoningCostUsd(),
			b.cachedPromptCostUsd(), b.totalCostUsd(), b.pricingPlanId(), b.pricingVersion(), b.sourceType(),
			b.metadataJson(), b.occurredAt()
		);

		assertThat(PayloadFingerprint.of(left)).isNotEqualTo(PayloadFingerprint.of(right));
	}

	@Test
	void invalidMetadataJsonIsRejected() {
		UsageLogCreateRequest b = base();

		assertThatThrownBy(() -> fingerprintWithMetadata(b, "{not json"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static String fingerprintWithMetadata(UsageLogCreateRequest b, String metadataJson) {
		return PayloadFingerprint.of(copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(),
			b.totalTokens(), b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(),
			metadataJson, b.occurredAt()));
	}

	private static UsageLogCreateRequest withOccurredAt(UsageLogCreateRequest b, LocalDateTime occurredAt) {
		return copy(b, b.promptTokens(), b.reasoningTokens(), b.cachedPromptTokens(), b.totalTokens(),
			b.promptCostUsd(), b.reasoningCostUsd(), b.totalCostUsd(), b.sourceType(), b.metadataJson(), occurredAt);
	}
}
