package com.tokenledgercloud.api.global.exception;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.tokenledgercloud.api.global.response.ApiFieldError;
import com.tokenledgercloud.api.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
				.body(ApiResponse.error(errorCode.getCode(), exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
		List<ApiFieldError> errors = exception.getBindingResult()
				.getFieldErrors()
				.stream()
				.map(this::toApiFieldError)
				.toList();

		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), ErrorCode.INVALID_INPUT.getMessage(), errors));
	}

	// 매핑되지 않은 경로(설정으로 꺼진 컨트롤러 포함)는 아래 500 처리로 가지 않고 404로 응답한다.
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
				.body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage()));
	}

	// 요청 본문을 읽을 수 없는 경우(JSON 문법 오류, 날짜·숫자 형식 오류 등)는 서버 오류가 아니라 잘못된 입력이다.
	// 예외 메시지에는 클라이언트가 보낸 값이 들어갈 수 있으므로 응답에도 로그에도 싣지 않는다.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
		log.debug("Unreadable request body: {}", exception.getClass().getSimpleName());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
				.body(ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), ErrorCode.INVALID_INPUT.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
		log.error("Unhandled exception occurred", exception);
		return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
				.body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
	}

	private ApiFieldError toApiFieldError(FieldError fieldError) {
		return new ApiFieldError(
				fieldError.getField(),
				// The rejected input is never echoed back: it may be sensitive (e.g. metadata text).
				null,
				fieldError.getDefaultMessage()
		);
	}

}