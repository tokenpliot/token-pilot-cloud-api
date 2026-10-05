package com.tokenledgercloud.api.global.exception;

import java.util.List;


import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
