package com.tokenledgercloud.api.domain.decision;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 판정 컨트롤러 전용 오류 변환(control-plane-v1.yaml). 다른 API의 오류 응답은 {@code GlobalExceptionHandler}가 그대로 맡는다.
 *
 * <p>어떤 오류도 정책 거부로 바꾸지 않는다. 응답의 {@code data.outcome}은 INDETERMINATE 또는 CONTROL_PLANE_UNAVAILABLE이다.
 * 처리하지 않는 예외(프레임워크의 405·415, 예상하지 못한 버그)는 전역 처리기로 넘어간다. 그 응답에는 {@code data.outcome}이 없지만
 * SDK는 상태코드만으로 INDETERMINATE 또는 CONTROL_PLANE_UNAVAILABLE을 정한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DecisionController.class)
public class ControlPlaneExceptionHandler {

	/** 동시 요청 충돌을 풀지 못했을 때. 같은 요청을 다시 보내면 안전하므로 곧바로 재시도할 수 있다. */
	static final int RETRY_AFTER_CONFLICT_SECONDS = 1;
	/** 저장소 장애일 때. */
	static final int RETRY_AFTER_STORE_DOWN_SECONDS = 5;

	@ExceptionHandler(InvalidDecisionRequestException.class)
	public ResponseEntity<ControlPlaneErrorResponse> handleInvalid(InvalidDecisionRequestException exception) {
		List<ControlPlaneErrorResponse.FieldError> errors = exception.violations().stream()
			.map(v -> new ControlPlaneErrorResponse.FieldError(v.field(), v.message())).toList();
		String message = exception.violations().stream().anyMatch(v -> "not allowed".equals(v.message()))
			? "Unknown field. Raw prompt text and other undefined fields are not accepted."
			: exception.getMessage();
		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, null, errors);
	}

	// 깨진 JSON, 알 수 없는 enum 값, 숫자 자리의 문자열 등. 예외 메시지가 클라이언트 값을 되울릴 수 있어 싣지 않는다.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ControlPlaneErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
		log.debug("Unreadable decision request body: {}", exception.getClass().getSimpleName());
		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is not valid JSON for this contract.", null, List.of());
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ControlPlaneErrorResponse> handleApi(ApiException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return switch (errorCode) {
			case INVALID_INPUT -> build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), null, List.of());
			case UNAUTHORIZED -> build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage(), null, List.of());
			case FORBIDDEN -> build(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), null, List.of());
			case NOT_FOUND -> build(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), null, List.of());
			case IDEMPOTENCY_CONFLICT -> build(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), null, List.of());
			case INGESTION_RETRY_LATER -> unavailable("Concurrent request could not be resolved. Retry the same request.",
				RETRY_AFTER_CONFLICT_SECONDS);
			default -> {
				// 계약에 이름이 없는 오류는 상태코드를 유지하고 기존 코드를 쓴다. 결과는 상태코드에서 정한다.
				HttpStatus status = HttpStatus.valueOf(errorCode.getStatus().value());
				yield build(status, errorCode.getCode(), exception.getMessage(), null, List.of());
			}
		};
	}

	// 저장소 장애는 정책 거부가 아니다. 고객의 onUnavailable 설정으로만 집행된다.
	@ExceptionHandler({DataAccessException.class, TransactionException.class})
	public ResponseEntity<ControlPlaneErrorResponse> handleStoreFailure(Exception exception) {
		log.error("Decision store unavailable", exception);
		return unavailable("Decision store is unavailable.", RETRY_AFTER_STORE_DOWN_SECONDS);
	}

	private ResponseEntity<ControlPlaneErrorResponse> unavailable(String message, int retryAfterSeconds) {
		return build(HttpStatus.SERVICE_UNAVAILABLE, "CONTROL_PLANE_UNAVAILABLE", message, retryAfterSeconds, List.of());
	}

	private ResponseEntity<ControlPlaneErrorResponse> build(HttpStatus status, String code, String message,
		Integer retryAfterSeconds, List<ControlPlaneErrorResponse.FieldError> errors) {
		// 결과는 SDK와 같은 규칙(ADR 0001 §4)으로 상태코드에서 정한다.
		DecisionOutcome outcome = DecisionOutcome.forErrorStatus(status.value());
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfterSeconds != null) {
			builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		}
		return builder.body(ControlPlaneErrorResponse.of(code, message, outcome, retryAfterSeconds, errors));
	}
}
