package com.tokenledgercloud.api.domain.usage.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * Runs each usage-log write and each post-conflict lookup in its own transaction.
 *
 * <p>A unique-key violation only rolls back the insert's own transaction, so the caller can catch it and keep
 * going. The lookups run in a fresh transaction so they see rows committed by the concurrent request that won
 * (a caller's open transaction may hold an older snapshot). Kept as a separate bean so the calls go through the
 * transactional proxy.
 */
@Component
@RequiredArgsConstructor
public class UsageLogWriter {

	private final UsageLogRepository usageLogRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UsageLog insertNew(UsageLogCreateRequest request, String eventId, String fingerprint) {
		UsageLog usageLog = UsageLog.builder()
			.organizationId(request.organizationId())
			.projectId(request.projectId())
			.apiKeyId(request.apiKeyId())
			.environment(request.environment())
			.requestId(request.requestId())
			.eventId(eventId)
			.payloadFingerprint(fingerprint)
			.provider(request.provider())
			.model(request.model())
			.promptTokens(request.promptTokens())
			.completionTokens(request.completionTokens())
			.reasoningTokens(safe(request.reasoningTokens()))
			.cachedPromptTokens(safe(request.cachedPromptTokens()))
			.totalTokens(request.resolvedTotalTokens())
			.promptCostUsd(request.promptCostUsd())
			.completionCostUsd(request.completionCostUsd())
			.reasoningCostUsd(safe(request.reasoningCostUsd()))
			.cachedPromptCostUsd(safe(request.cachedPromptCostUsd()))
			.totalCostUsd(request.resolvedTotalCostUsd())
			.pricingPlanId(request.pricingPlanId())
			.pricingVersion(request.pricingVersion())
			.sourceType(request.sourceType())
			.metadataJson(request.metadataJson())
			.occurredAt(request.occurredAt())
			.build();

		// Flush so a unique violation surfaces here, inside this transaction, rather than at commit.
		return usageLogRepository.saveAndFlush(usageLog);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<UsageLog> findByEventId(String projectId, String environment, String eventId) {
		return usageLogRepository.findByProjectIdAndEnvironmentAndEventId(projectId, environment, eventId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<UsageLog> findByRequestId(String projectId, String environment, String requestId) {
		return usageLogRepository.findByProjectIdAndEnvironmentAndRequestId(projectId, environment, requestId);
	}

	private Long safe(Long value) {
		return value == null ? 0L : value;
	}

	private java.math.BigDecimal safe(java.math.BigDecimal value) {
		return value == null ? java.math.BigDecimal.ZERO : value;
	}
}
