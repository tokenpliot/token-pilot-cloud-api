package com.tokenledgercloud.api.domain.usage.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UsageLogCreateRequest(
	@NotBlank @Size(max = 36) String organizationId,
	@NotBlank @Size(max = 36) String projectId,
	@Size(max = 36) String apiKeyId,
	@NotBlank @Size(max = 20) String environment,
	@Size(max = 100) String requestId,
	@NotBlank @Size(max = 50) String provider,
	@NotBlank @Size(max = 100) String model,
	@NotNull @PositiveOrZero Long promptTokens,
	@NotNull @PositiveOrZero Long completionTokens,
	@PositiveOrZero Long reasoningTokens,
	@PositiveOrZero Long cachedPromptTokens,
	Long totalTokens,
	@NotNull @DecimalMin("0.000000") BigDecimal promptCostUsd,
	@NotNull @DecimalMin("0.000000") BigDecimal completionCostUsd,
	@DecimalMin("0.000000") BigDecimal reasoningCostUsd,
	@DecimalMin("0.000000") BigDecimal cachedPromptCostUsd,
	BigDecimal totalCostUsd,
	@Size(max = 36) String pricingPlanId,
	@NotBlank @Size(max = 50) String pricingVersion,
	@Size(max = 30) String sourceType,
	String metadataJson,
	@NotNull LocalDateTime occurredAt,
	@Size(max = 100) String eventId
) {

	/** Legacy shape without {@code eventId}; the idempotency key falls back to {@code requestId}. */
	public UsageLogCreateRequest(
		String organizationId,
		String projectId,
		String apiKeyId,
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
		String metadataJson,
		LocalDateTime occurredAt
	) {
		this(organizationId, projectId, apiKeyId, environment, requestId, provider, model, promptTokens,
			completionTokens, reasoningTokens, cachedPromptTokens, totalTokens, promptCostUsd, completionCostUsd,
			reasoningCostUsd, cachedPromptCostUsd, totalCostUsd, pricingPlanId, pricingVersion, sourceType,
			metadataJson, occurredAt, null);
	}
}
