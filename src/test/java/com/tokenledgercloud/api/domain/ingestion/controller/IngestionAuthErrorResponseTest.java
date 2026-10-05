package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tokenledgercloud.api.domain.ingestion.service.IngestionService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.exception.GlobalExceptionHandler;

/** How authentication failures reach the client: status, code and a body that never contains the key. */
@ExtendWith(MockitoExtension.class)
class IngestionAuthErrorResponseTest {

	private static final String SECRET_KEY = "tpk_live_SECRET-KEY-VALUE";

	@Mock
	private IngestionService ingestionService;

	@InjectMocks
	private IngestionController ingestionController;

	private MockMvc mockMvc() {
		return MockMvcBuilders.standaloneSetup(ingestionController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private static final String EVENT = """
		{ "projectKey": "support-copilot", "environment": "prod", "requestId": "req_1",
		  "provider": "openai", "model": "gpt-4o-mini", "promptTokens": 1, "completionTokens": 1,
		  "pricingVersion": "v", "occurredAt": "2026-05-06T10:00:00Z" }
		""";

	private static final String BATCH = """
		{ "projectKey": "support-copilot", "environment": "prod", "items": [
		  { "requestId": "req_1", "provider": "openai", "model": "gpt-4o-mini", "promptTokens": 1,
		    "completionTokens": 1, "pricingVersion": "v", "occurredAt": "2026-05-06T10:00:00Z" } ] }
		""";

	private void assertFailure(ApiException failure, int httpStatus, String code, String path, String body)
		throws Exception {
		if (path.endsWith("batch")) {
			given(ingestionService.collectBatch(eq(SECRET_KEY), any())).willThrow(failure);
		} else {
			given(ingestionService.collectEvent(eq(SECRET_KEY), any())).willThrow(failure);
		}

		var result = mockMvc().perform(post(path)
				.header("X-API-Key", SECRET_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value(code));

		assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("SECRET-KEY-VALUE");
	}

	@Test
	void authenticationFailuresMapToTheirHttpStatusForSingleEventsAndBatches() throws Exception {
		for (String path : new String[] {"/api/ingestion/events", "/api/ingestion/events/batch"}) {
			String body = path.endsWith("batch") ? BATCH : EVENT;
			assertFailure(new ApiException(ErrorCode.UNAUTHORIZED, "Invalid project API key."), 401, "COMMON-401",
				path, body);
			assertFailure(new ApiException(ErrorCode.FORBIDDEN, "Project API key is not allowed for this project."),
				403, "COMMON-403", path, body);
			assertFailure(new ApiException(ErrorCode.NOT_FOUND, "Project was not found for projectKey."), 404,
				"COMMON-404", path, body);
		}
	}
}
