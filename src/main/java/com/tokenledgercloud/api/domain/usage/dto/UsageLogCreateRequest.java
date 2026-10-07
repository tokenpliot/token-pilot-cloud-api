package com.tokenledgercloud.api.domain.usage.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
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
	@NotNull @DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal promptCostUsd,
	@NotNull @DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal completionCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal reasoningCostUsd,
	@DecimalMin("0.000000") @DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal cachedPromptCostUsd,
	@DecimalMax(UsageLogCreateRequest.MAX_USD) BigDecimal totalCostUsd,
	@Size(max = 36) String pricingPlanId,
	@NotBlank @Size(max = 50) String pricingVersion,
	@Size(max = 30) String sourceType,
	String metadataJson,
	@NotNull LocalDateTime occurredAt,
	@Size(max = 100) String eventId
) {

	/** Largest value of the {@code decimal(18,6)} USD columns of {@code usage_events}. */
	public static final String MAX_USD = "999999999999.999999";

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

	/** The client's total, or the sum of the parts. Throws {@link ArithmeticException} on {@code long} overflow. */
	public long resolvedTotalTokens() {
		if (totalTokens != null) {
			return totalTokens;
		}
		return Math.addExact(Math.addExact(zero(promptTokens), zero(completionTokens)),
			Math.addExact(zero(reasoningTokens), zero(cachedPromptTokens)));
	}

	/** The client's total, or the sum of the parts. */
	public BigDecimal resolvedTotalCostUsd() {
		if (totalCostUsd != null) {
			return totalCostUsd;
		}
		return zero(promptCostUsd).add(zero(completionCostUsd)).add(zero(reasoningCostUsd))
			.add(zero(cachedPromptCostUsd));
	}

	private static long zero(Long value) {
		return value == null ? 0L : value;
	}

	private static BigDecimal zero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}
}
