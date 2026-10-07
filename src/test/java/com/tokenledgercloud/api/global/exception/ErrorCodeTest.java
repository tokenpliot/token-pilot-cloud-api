package com.tokenledgercloud.api.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

	@Test
	void serverErrorsAndThrottlingAreRetryable() {
		assertThat(ErrorCode.INGESTION_RETRY_LATER.isRetryable()).isTrue();
		assertThat(ErrorCode.INTERNAL_SERVER_ERROR.isRetryable()).isTrue();
	}

	@Test
	void clientErrorsAreNotRetryable() {
		assertThat(ErrorCode.INVALID_INPUT.isRetryable()).isFalse();
		assertThat(ErrorCode.UNAUTHORIZED.isRetryable()).isFalse();
		assertThat(ErrorCode.FORBIDDEN.isRetryable()).isFalse();
		assertThat(ErrorCode.NOT_FOUND.isRetryable()).isFalse();
		assertThat(ErrorCode.IDEMPOTENCY_CONFLICT.isRetryable()).isFalse();
	}
}
