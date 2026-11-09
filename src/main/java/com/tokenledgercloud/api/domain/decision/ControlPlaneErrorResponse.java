package com.tokenledgercloud.api.domain.decision;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * control-plane-v1.yaml의 ErrorEnvelope. 기존 {@code ApiResponse}와 모양은 같지만 오류 필드가 {@code {field, message}}이고
 * {@code data.outcome}이 있다. 이 응답은 {@code DENY_*}를 담을 수 없다(outcome 타입이 정책 거부를 표현하지 못한다).
 */
public record ControlPlaneErrorResponse(boolean success, String code, String message, Data data,
										List<FieldError> errors, LocalDateTime timestamp) {

	/** 오류가 담을 수 있는 결과는 INDETERMINATE와 CONTROL_PLANE_UNAVAILABLE뿐이다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Data(DecisionOutcome outcome, Integer retryAfterSeconds) {
		public Data {
			if (outcome != DecisionOutcome.INDETERMINATE && outcome != DecisionOutcome.CONTROL_PLANE_UNAVAILABLE) {
				throw new IllegalArgumentException("An error response cannot carry " + outcome);
			}
		}
	}

	public record FieldError(String field, String message) {
	}

	public static ControlPlaneErrorResponse of(String code, String message, DecisionOutcome outcome,
		Integer retryAfterSeconds, List<FieldError> errors) {
		return new ControlPlaneErrorResponse(false, code, message, new Data(outcome, retryAfterSeconds), errors,
			LocalDateTime.now());
	}
}
