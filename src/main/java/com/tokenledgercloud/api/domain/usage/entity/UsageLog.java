package com.tokenledgercloud.api.domain.usage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
	name = "usage_events",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_usage_events_project_env_request", columnNames = {"project_id", "environment", "request_id"}),
		@UniqueConstraint(name = "uk_usage_events_project_env_event", columnNames = {"project_id", "environment", "event_id"})
	},
	indexes = {
		@Index(name = "idx_usage_events_project_time", columnList = "project_id, environment, occurred_at"),
		@Index(name = "idx_usage_events_provider_model", columnList = "provider, model, occurred_at")
	}
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageLog {

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "organization_id", nullable = false, length = 36)
	private String organizationId;

	@Column(name = "project_id", nullable = false, length = 36)
	private String projectId;

	@Column(name = "api_key_id", length = 36)
	private String apiKeyId;

	@Column(nullable = false, length = 20)
	private String environment;

	@Column(name = "request_id", length = 100)
	private String requestId;

	@Column(name = "event_id", length = 100)
	private String eventId;

	@Column(name = "payload_fingerprint", length = 64, columnDefinition = "char(64)")
	private String payloadFingerprint;

	@Column(nullable = false, length = 50)
	private String provider;

	@Column(nullable = false, length = 100)
	private String model;

	@Column(name = "prompt_tokens", nullable = false)
	private Long promptTokens;

	@Column(name = "completion_tokens", nullable = false)
	private Long completionTokens;

	@Column(name = "reasoning_tokens", nullable = false)
	private Long reasoningTokens;

	@Column(name = "cached_prompt_tokens", nullable = false)
	private Long cachedPromptTokens;

	@Column(name = "total_tokens", nullable = false)
	private Long totalTokens;

	@Column(name = "prompt_cost_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal promptCostUsd;

	@Column(name = "completion_cost_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal completionCostUsd;

	@Column(name = "reasoning_cost_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal reasoningCostUsd;

	@Column(name = "cached_prompt_cost_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal cachedPromptCostUsd;

	@Column(name = "total_cost_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal totalCostUsd;

	@Column(name = "pricing_plan_id", length = 36)
	private String pricingPlanId;

	@Column(name = "pricing_version", nullable = false, length = 50)
	private String pricingVersion;

	@Column(name = "source_type", nullable = false, length = 30)
	private String sourceType;

	@Column(name = "metadata_json", columnDefinition = "json")
	private String metadataJson;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void prePersist() {
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
		promptTokens = safe(promptTokens);
		completionTokens = safe(completionTokens);
		reasoningTokens = safe(reasoningTokens);
		cachedPromptTokens = safe(cachedPromptTokens);
		promptCostUsd = safe(promptCostUsd);
		completionCostUsd = safe(completionCostUsd);
		reasoningCostUsd = safe(reasoningCostUsd);
		cachedPromptCostUsd = safe(cachedPromptCostUsd);
		if (totalTokens == null) {
			totalTokens = promptTokens + completionTokens + reasoningTokens + cachedPromptTokens;
		}
		if (totalCostUsd == null) {
			totalCostUsd = promptCostUsd
				.add(completionCostUsd)
				.add(reasoningCostUsd)
				.add(cachedPromptCostUsd);
		}
		if (sourceType == null || sourceType.isBlank()) {
			sourceType = "sdk";
		}
	}

	private Long safe(Long value) {
		return value == null ? 0L : value;
	}

	private BigDecimal safe(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}
}
