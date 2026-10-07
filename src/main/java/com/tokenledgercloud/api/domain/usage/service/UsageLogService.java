package com.tokenledgercloud.api.domain.usage.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageLogService {

	private static final String UNIQUE_SQL_STATE = "23505";
	private static final int MYSQL_DUPLICATE_ENTRY = 1062;
	private static final BigDecimal MAX_USD = new BigDecimal(UsageLogCreateRequest.MAX_USD);

	private final UsageLogRepository usageLogRepository;
	private final UsageLogWriter usageLogWriter;

	/**
	 * Legacy create: an existing row for the same {@code requestId} is returned as-is, without comparing payloads.
	 * Used by the internal endpoint; ingestion uses {@link #createIdempotent}.
	 */
	public UsageLogResponse create(UsageLogCreateRequest request) {
		return createOrGet(request, false).log();
	}

	/**
	 * Idempotent create keyed by {@code (project, environment, eventId ?? requestId)}.
	 * Same key and same payload returns the stored log with {@code duplicate=true};
	 * same key with a different payload throws {@link ErrorCode#IDEMPOTENCY_CONFLICT}.
	 *
	 * <p>Deliberately not transactional: the pre-check runs in its own short transaction and the insert in
	 * {@link UsageLogWriter}'s {@code REQUIRES_NEW}, so a single request never holds two connections. The unique
	 * keys arbitrate concurrent requests: the loser's insert fails, it re-reads the winner's row in a fresh
	 * transaction and compares fingerprints.
	 */
	public UsageLogCreateResult createIdempotent(UsageLogCreateRequest request) {
		return createOrGet(request, true);
	}

	private UsageLogCreateResult createOrGet(UsageLogCreateRequest request, boolean enforcePayload) {
		try {
			return doCreateOrGet(request, enforcePayload);
		} catch (ConcurrencyFailureException exception) {
			// Deadlock, lock wait timeout, serialization failure: nothing was stored and re-sending is safe.
			log.warn("Concurrency failure while storing usage log ({})", exception.getClass().getSimpleName());
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		}
	}

	private UsageLogCreateResult doCreateOrGet(UsageLogCreateRequest request, boolean enforcePayload) {
		validateTotals(request);
		String fingerprint = fingerprint(request);
		String eventId = blankToNull(request.eventId());
		boolean hasRequestId = request.requestId() != null && !request.requestId().isBlank();

		Optional<UsageLogCreateResult> found = resolveExisting(
			request, eventId, hasRequestId, fingerprint, enforcePayload, directLookup());
		if (found.isPresent()) {
			return found.get();
		}

		try {
			return new UsageLogCreateResult(
				UsageLogResponse.from(usageLogWriter.insertNew(request, eventId, fingerprint)), false);
		} catch (DataIntegrityViolationException exception) {
			Optional<UsageLogCreateResult> raced = resolveExisting(
				request, eventId, hasRequestId, fingerprint, enforcePayload, freshLookup());
			if (raced.isPresent()) {
				return raced.get();
			}
			if (isUniqueViolation(exception)) {
				// The competing row vanished or cannot be matched; the same request is safe to retry.
				log.warn("Unique violation without a matching row (project={}, environment={})",
					request.projectId(), request.environment());
				throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
			}
			throw exception;
		}
	}

	private Optional<UsageLogCreateResult> resolveExisting(
		UsageLogCreateRequest request,
		String eventId,
		boolean hasRequestId,
		String fingerprint,
		boolean enforcePayload,
		Lookup lookup
	) {
		Optional<UsageLog> existing = eventId != null
			? lookup.byEventId(request, eventId)
			: hasRequestId ? lookup.byRequestId(request) : Optional.empty();

		if (existing.isPresent()) {
			UsageLog stored = existing.get();
			if (enforcePayload && !samePayload(stored, request, eventId, fingerprint)) {
				throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
			}
			return Optional.of(new UsageLogCreateResult(UsageLogResponse.from(stored), true));
		}

		if (eventId != null && hasRequestId) {
			// The request_id unique key also applies: another event cannot reuse a stored requestId.
			// A row with this same eventId can show up here when a concurrent request committed between the two
			// lookups; that is the same key, so it is compared like any other existing row.
			Optional<UsageLog> sameRequest = lookup.byRequestId(request);
			if (sameRequest.isPresent()) {
				UsageLog stored = sameRequest.get();
				boolean sameKey = eventId.equals(blankToNull(stored.getEventId()));
				if (enforcePayload && !(sameKey && samePayload(stored, request, eventId, fingerprint))) {
					throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return Optional.of(new UsageLogCreateResult(UsageLogResponse.from(stored), true));
			}
		}

		return Optional.empty();
	}

	/** Pre-check: plain repository reads, joining a caller transaction if there is one. */
	private Lookup directLookup() {
		return new Lookup(
			(request, eventId) -> usageLogRepository.findByProjectIdAndEnvironmentAndEventId(
				request.projectId(), request.environment(), eventId),
			request -> usageLogRepository.findByProjectIdAndEnvironmentAndRequestId(
				request.projectId(), request.environment(), request.requestId())
		);
	}

	/** After a conflict: reads in a new transaction that sees the competing request's committed row. */
	private Lookup freshLookup() {
		return new Lookup(
			(request, eventId) -> usageLogWriter.findByEventId(request.projectId(), request.environment(), eventId),
			request -> usageLogWriter.findByRequestId(
				request.projectId(), request.environment(), request.requestId())
		);
	}

	private record Lookup(
		BiFunction<UsageLogCreateRequest, String, Optional<UsageLog>> byEventId,
		Function<UsageLogCreateRequest, Optional<UsageLog>> byRequestId
	) {

		Optional<UsageLog> byEventId(UsageLogCreateRequest request, String eventId) {
			return byEventId.apply(request, eventId);
		}

		Optional<UsageLog> byRequestId(UsageLogCreateRequest request) {
			return byRequestId.apply(request);
		}
	}

	private boolean isUniqueViolation(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation
				&& violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
				return true;
			}
			if (cause instanceof SQLException sql && (UNIQUE_SQL_STATE.equals(sql.getSQLState())
				|| sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY)) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
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

	/**
	 * A derived total can overflow the columns even when every part is in range. Rejected as invalid input so a
	 * batch marks only this item REJECTED instead of failing the whole request on a database error.
	 */
	private void validateTotals(UsageLogCreateRequest request) {
		try {
			request.resolvedTotalTokens();
		} catch (ArithmeticException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "totalTokens is out of range.");
		}
		if (request.resolvedTotalCostUsd().compareTo(MAX_USD) > 0) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "totalCostUsd is out of range.");
		}
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

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
