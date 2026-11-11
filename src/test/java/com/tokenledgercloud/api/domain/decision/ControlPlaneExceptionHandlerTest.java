package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** 서버 장애는 정책 거부가 아니라 CONTROL_PLANE_UNAVAILABLE(503, Retry-After)로 나가야 한다. */
class ControlPlaneExceptionHandlerTest {

	private final ControlPlaneExceptionHandler handler = new ControlPlaneExceptionHandler();
	private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private JsonNode json(ResponseEntity<ControlPlaneErrorResponse> response) throws Exception {
		return ControlPlaneSpec.assertValid("ErrorEnvelope", mapper.writeValueAsString(response.getBody()));
	}

	@Test
	void unresolvedConcurrencyConflictBecomesControlPlaneUnavailableWithRetryAfter() throws Exception {
		ResponseEntity<ControlPlaneErrorResponse> response = handler.handleApi(new ApiException(ErrorCode.INGESTION_RETRY_LATER));

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
		JsonNode body = json(response);
		assertThat(body.at("/code").asText()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
		assertThat(body.at("/data/outcome").asText()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
		assertThat(body.at("/data/retryAfterSeconds").asInt()).isEqualTo(1);
	}

	@Test
	void storeFailuresBecomeControlPlaneUnavailable() throws Exception {
		for (Exception failure : new Exception[] {new QueryTimeoutException("db"), new CannotCreateTransactionException("no connection")}) {
			ResponseEntity<ControlPlaneErrorResponse> response = handler.handleStoreFailure(failure);

			assertThat(response.getStatusCode().value()).isEqualTo(503);
			assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
			assertThat(json(response).at("/data/outcome").asText()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
			// 내부 예외 메시지(접속 정보 등)는 응답에 싣지 않는다.
			assertThat(mapper.writeValueAsString(response.getBody())).doesNotContain("no connection");
		}
	}

	@Test
	void clientErrorsNeverCarryAControlPlaneUnavailableOutcomeOrARetryAfter() throws Exception {
		for (ErrorCode code : new ErrorCode[] {ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND, ErrorCode.IDEMPOTENCY_CONFLICT, ErrorCode.INVALID_INPUT}) {
			ResponseEntity<ControlPlaneErrorResponse> response = handler.handleApi(new ApiException(code, "message"));

			assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).as(code.name()).isNull();
			assertThat(json(response).at("/data/outcome").asText()).as(code.name()).isEqualTo("INDETERMINATE");
		}
	}

	@Test
	void errorBodyCannotBeBuiltWithAPolicyDenial() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> new ControlPlaneErrorResponse.Data(DecisionOutcome.DENY_BUDGET, null));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> new ControlPlaneErrorResponse.Data(DecisionOutcome.ALLOW, null));
	}
}
