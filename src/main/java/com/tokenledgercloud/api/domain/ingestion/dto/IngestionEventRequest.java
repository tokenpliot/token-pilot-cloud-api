package com.tokenledgercloud.api.domain.ingestion.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record IngestionEventRequest(
	@NotBlank String projectKey,
	@NotBlank String environment,
	@NotBlank String requestId,
	@NotBlank String provider,
	@NotBlank String model,
	@NotNull @PositiveOrZero Long promptTokens,
	@NotNull @PositiveOrZero Long completionTokens,
	@PositiveOrZero Long reasoningTokens,
	@PositiveOrZero Long cachedPromptTokens,
	@PositiveOrZero Long totalTokens,
	@DecimalMin("0.000000") BigDecimal promptCostUsd,
	@DecimalMin("0.000000") BigDecimal completionCostUsd,
	@DecimalMin("0.000000") BigDecimal reasoningCostUsd,
	@DecimalMin("0.000000") BigDecimal cachedPromptCostUsd,
	@DecimalMin("0.000000") BigDecimal totalCostUsd,
	String pricingPlanId,
	@NotBlank String pricingVersion,
	String sourceType,
	Map<String, Object> metadata,
	@NotNull OffsetDateTime occurredAt,
	@Size(max = 100) String eventId
) {

	/** Legacy shape without {@code eventId}; the idempotency key falls back to {@code requestId}. */
	public IngestionEventRequest(
		String projectKey,
		String environment,
		String requestId,
		String provider,
		String model,
		Long promptTokens,
		Long completionTokens,
		Long reasoningTokens,
		Long cachedPromptTokens,
		Long totalTokens,
		BigDecimal promptCostUsd,
		BigDecimal completionCostUsd,
		BigDecimal reasoningCostUsd,
		BigDecimal cachedPromptCostUsd,
		BigDecimal totalCostUsd,
		String pricingPlanId,
		String pricingVersion,
		String sourceType,
		Map<String, Object> metadata,
		OffsetDateTime occurredAt
	) {
		this(projectKey, environment, requestId,
			provider, model, promptTokens, completionTokens, reasoningTokens, cachedPromptTokens,
			totalTokens, promptCostUsd, completionCostUsd, reasoningCostUsd, cachedPromptCostUsd, totalCostUsd,
			pricingPlanId, pricingVersion, sourceType, metadata, occurredAt, null);
	}
}
