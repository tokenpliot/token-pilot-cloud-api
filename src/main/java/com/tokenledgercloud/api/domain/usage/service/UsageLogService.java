package com.tokenledgercloud.api.domain.usage.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.ingestion.service.PayloadFingerprint;
import com.tokenledgercloud.api.domain.usage.dto.UsageEventItemResponse;
import com.tokenledgercloud.api.domain.usage.dto.UsageEventListResponse;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateResult;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogResponse;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UsageLogService {

	private final UsageLogRepository usageLogRepository;

	/**
	 * Legacy create: an existing row for the same {@code requestId} is returned as-is, without comparing payloads.
	 * Used by the internal endpoint; ingestion uses {@link #createIdempotent}.
	 */
	@Transactional
	public UsageLogResponse create(UsageLogCreateRequest request) {
		return createOrGet(request, false).log();
	}

	/**
	 * Idempotent create keyed by {@code (project, environment, eventId ?? requestId)}.
	 * Same key and same payload returns the stored log with {@code duplicate=true};
	 * same key with a different payload throws {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
	 * The conflict is detected before any write, so the surrounding transaction is not marked rollback-only.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public UsageLogCreateResult createIdempotent(UsageLogCreateRequest request) {
		return createOrGet(request, true);
	}

	private UsageLogCreateResult createOrGet(UsageLogCreateRequest request, boolean enforcePayload) {
		String fingerprint = fingerprint(request);
		String eventId = blankToNull(request.eventId());
		boolean hasRequestId = request.requestId() != null && !request.requestId().isBlank();

		Optional<UsageLog> existing = eventId != null
			? usageLogRepository.findByProjectIdAndEnvironmentAndEventId(request.projectId(), request.environment(), eventId)
			: hasRequestId ? findByRequestId(request) : Optional.empty();

		if (existing.isPresent()) {
			UsageLog stored = existing.get();
			if (enforcePayload && !samePayload(stored, request, eventId, fingerprint)) {
				throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return new UsageLogCreateResult(UsageLogResponse.from(stored), true);
		}

		if (eventId != null && hasRequestId) {
			// A new eventId cannot reuse a requestId already stored for another event (unique key on request_id).
			Optional<UsageLog> sameRequest = findByRequestId(request);
			if (sameRequest.isPresent()) {
				if (enforcePayload) {
					throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return new UsageLogCreateResult(UsageLogResponse.from(sameRequest.get()), true);
			}
		}

		return new UsageLogCreateResult(UsageLogResponse.from(saveNew(request, eventId, fingerprint)), false);
	}

	private Optional<UsageLog> findByRequestId(UsageLogCreateRequest request) {
		return usageLogRepository.findByProjectIdAndEnvironmentAndRequestId(
			request.projectId(),
			request.environment(),
			request.requestId()
		);
	}

	/**
	 * Legacy rows (no stored fingerprint) cannot be compared and count as the same payload.
	 * When keyed by eventId, a different requestId is a different call and therefore a conflict.
	 */
	private boolean samePayload(UsageLog stored, UsageLogCreateRequest request, String eventId, String fingerprint) {
		if (eventId != null && !Objects.equals(blankToNull(stored.getRequestId()), blankToNull(request.requestId()))) {
			return false;
		}
		return stored.getPayloadFingerprint() == null || stored.getPayloadFingerprint().equals(fingerprint);
	}

	private String fingerprint(UsageLogCreateRequest request) {
		try {
			return PayloadFingerprint.of(request);
		} catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, exception.getMessage());
		}
	}

	@Transactional(readOnly = true)
	public UsageEventListResponse getRecentEvents(
		String projectId,
		String environment,
		String provider,
		String model,
		String cursor,
		Integer size
	) {
		int limit = size == null ? 20 : Math.min(size, 100);

		LocalDateTime cursorOccurredAt = null;

		if (cursor != null && !cursor.isBlank()) {
			UsageLog cursorLog = usageLogRepository.findById(cursor)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

			cursorOccurredAt = cursorLog.getOccurredAt();
		}

		List<UsageLog> logs = usageLogRepository.findRecentUsageEvents(
			blankToNull(projectId),
			blankToNull(environment),
			blankToNull(provider),
			blankToNull(model),
			cursorOccurredAt,
			PageRequest.of(0, limit)
		);

		List<UsageEventItemResponse> items = logs.stream()
			.map(UsageEventItemResponse::from)
			.toList();

		String nextCursor = items.isEmpty()
			? null
			: items.get(items.size() - 1).eventId();

		return new UsageEventListResponse(items, nextCursor);
	}

	private UsageLog saveNew(UsageLogCreateRequest request, String eventId, String fingerprint) {
		Long totalTokens = request.totalTokens() != null
			? request.totalTokens()
			: request.promptTokens()
				+ request.completionTokens()
				+ safe(request.reasoningTokens())
				+ safe(request.cachedPromptTokens());

		var totalCostUsd = request.totalCostUsd() != null
			? request.totalCostUsd()
			: request.promptCostUsd()
				.add(request.completionCostUsd())
				.add(safe(request.reasoningCostUsd()))
				.add(safe(request.cachedPromptCostUsd()));

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
			.totalTokens(totalTokens)
			.promptCostUsd(request.promptCostUsd())
			.completionCostUsd(request.completionCostUsd())
			.reasoningCostUsd(safe(request.reasoningCostUsd()))
			.cachedPromptCostUsd(safe(request.cachedPromptCostUsd()))
			.totalCostUsd(totalCostUsd)
			.pricingPlanId(request.pricingPlanId())
			.pricingVersion(request.pricingVersion())
			.sourceType(request.sourceType())
			.metadataJson(request.metadataJson())
			.occurredAt(request.occurredAt())
			.build();

		return usageLogRepository.save(usageLog);
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private Long safe(Long value) {
		return value == null ? 0L : value;
	}

	private java.math.BigDecimal safe(java.math.BigDecimal value) {
		return value == null ? java.math.BigDecimal.ZERO : value;
	}
}