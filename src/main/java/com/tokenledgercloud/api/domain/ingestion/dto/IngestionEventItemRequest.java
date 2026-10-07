package com.tokenledgercloud.api.domain.ingestion.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.tokenledgercloud.api.domain.ingestion.validation.ValidIngestionMetadata;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;

public record IngestionEventItemRequest(
	@NotBlank @Size(max = 100) String requestId,
	@NotBlank @Size(max = 50) String provider,
	@NotBlank @Size(max = 100) String model,
	@NotNull @PositiveOrZero Long promptTokens,
	@NotNull @PositiveOrZero Long completionTokens,
	@PositiveOrZero Long reasoningTokens,
	@PositiveOrZero Long cachedPromptTokens,
	@PositiveOrZero Long totalTokens,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal promptCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal completionCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal reasoningCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal cachedPromptCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal totalCostUsd,
	@Size(max = 36) String pricingPlanId,
	@NotBlank @Size(max = 50) String pricingVersion,
	@Size(max = 30) String sourceType,
	@ValidIngestionMetadata Map<String, Object> metadata,
	@NotNull OffsetDateTime occurredAt,
	@Size(max = 100) String eventId
) {

	/** Legacy shape without {@code eventId}; the idempotency key falls back to {@code requestId}. */
	public IngestionEventItemRequest(
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
		this(requestId,
			provider, model, promptTokens, completionTokens, reasoningTokens, cachedPromptTokens,
			totalTokens, promptCostUsd, completionCostUsd, reasoningCostUsd, cachedPromptCostUsd, totalCostUsd,
			pricingPlanId, pricingVersion, sourceType, metadata, occurredAt, null);
	}
}
