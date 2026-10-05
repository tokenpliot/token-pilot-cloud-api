package com.tokenledgercloud.api.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-400", "Invalid request input."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-401", "Authentication is required."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON-403", "You do not have permission to access this resource."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-404", "The requested resource was not found."),
	CONFLICT(HttpStatus.CONFLICT, "COMMON-409", "The request conflicts with current resource state."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "An unexpected server error occurred."),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "INGESTION-409", "Idempotency key was already used with a different payload."),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "INGESTION-413", "Request body is too large."),
	INGESTION_RETRY_LATER(HttpStatus.SERVICE_UNAVAILABLE, "INGESTION-503", "Could not resolve a concurrent request. Retry the same request."),
	DUPLICATE_MEMBER_EMAIL(HttpStatus.CONFLICT, "MEMBER-409", "Email already registered."),
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER-404", "Member not found."),
	UNSUPPORTED_AUTHENTICATION(HttpStatus.UNAUTHORIZED, "AUTH-401", "Unsupported authentication type."),
	INVALID_MONTH(HttpStatus.BAD_REQUEST, "BUDGET-400", "Invalid month format."),
	INVALID_PERIOD_TYPE(HttpStatus.BAD_REQUEST, "BUDGET-401", "Unsupported budget period type."),
	BUDGET_NOT_FOUND(HttpStatus.NOT_FOUND, "BUDGET-404", "Budget not found."),
	INVALID_EVENT_TYPE(HttpStatus.BAD_REQUEST, "EVENT-400", "Invalid event type."),
	INVALID_PERIOD(HttpStatus.BAD_REQUEST, "DASHBOARD-400", "Unsupported period. Use today, week, or month."),
	PRICING_CATALOG_NOT_FOUND(HttpStatus.NOT_FOUND, "PRICING-404", "Pricing catalog not found."),
	INVALID_PRICING_EFFECTIVE_PERIOD(HttpStatus.BAD_REQUEST, "PRICING-400", "Invalid pricing effective period."),
	PRICING_CATALOG_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PRICING-500", "Failed to generate pricing catalog.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	/** Whether the same request may succeed if sent again (ADR 0001 section 4: 408, 429 and 5xx). */
	public boolean isRetryable() {
		int value = status.value();
		return value == 408 || value == 429 || status.is5xxServerError();
	}
}
