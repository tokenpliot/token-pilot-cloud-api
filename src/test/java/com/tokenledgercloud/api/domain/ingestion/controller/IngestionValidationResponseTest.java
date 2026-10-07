package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventResponse;
import com.tokenledgercloud.api.domain.ingestion.service.IngestionService;
import com.tokenledgercloud.api.global.exception.GlobalExceptionHandler;

/** Request validation outcomes for schemaVersion and metadata, and that error bodies never reflect input. */
@ExtendWith(MockitoExtension.class)
class IngestionValidationResponseTest {

	@Mock
	private IngestionService ingestionService;

	@InjectMocks
	private IngestionController ingestionController;

	private MockMvc mockMvc() {
		return MockMvcBuilders.standaloneSetup(ingestionController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private static String event(String extraFields) {
		return """
			{
			  "projectKey": "support-copilot", "environment": "prod", "requestId": "req_1",
			  "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 10, "completionTokens": 5, "pricingVersion": "2026-05-01",
			  "occurredAt": "2026-05-06T10:00:00Z"%s
			}
			""".formatted(extraFields);
	}

	private static String batch(String extraFields) {
		return """
			{
			  "projectKey": "support-copilot", "environment": "prod"%s,
			  "items": [ {
			    "requestId": "req_1", "provider": "openai", "model": "gpt-4o-mini",
			    "promptTokens": 10, "completionTokens": 5, "pricingVersion": "2026-05-01",
			    "occurredAt": "2026-05-06T10:00:00Z",
			    "metadata": { "prompt": "SECRET-ITEM-TEXT" } } ]
			}
			""".formatted(extraFields);
	}

	private String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getContentAsString();
	}

	@Test
	void forbiddenMetadataKeyDoesNotFailValidationAndIsNotReflected() throws Exception {
		// default (non-strict) mode: the request is accepted; the service drops the key before storing it
		given(ingestionService.collectEvent(any(), any())).willReturn(new IngestionEventResponse("usage-1", true));

		var result = mockMvc().perform(post("/api/ingestion/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content(event(", \"metadata\": {\"prompt\": \"TOP-SECRET-PROMPT-TEXT\"}")))
			.andExpect(status().isCreated());

		assertThat(body(result)).doesNotContain("TOP-SECRET-PROMPT-TEXT");
	}

	@Test
	void unknownSchemaVersionIs400AndIsNotEchoed() throws Exception {
		var result = mockMvc().perform(post("/api/ingestion/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content(event(", \"schemaVersion\": \"2099-01-01\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.errors[0].field").value("schemaVersion"))
			.andExpect(jsonPath("$.errors[0].reason").value(org.hamcrest.Matchers.containsString("2026-10-01")))
			.andExpect(jsonPath("$.errors[0].rejectedValue").value(nullValue()));

		assertThat(body(result)).doesNotContain("2099-01-01");
		verifyNoInteractions(ingestionService);
	}

	@Test
	void absentAndSupportedSchemaVersionAreAccepted() throws Exception {
		given(ingestionService.collectEvent(any(), any())).willReturn(new IngestionEventResponse("usage-1", true));

		mockMvc().perform(post("/api/ingestion/events").contentType(MediaType.APPLICATION_JSON).content(event("")))
			.andExpect(status().isCreated());
		mockMvc().perform(post("/api/ingestion/events").contentType(MediaType.APPLICATION_JSON)
				.content(event(", \"schemaVersion\": \"2026-10-01\"")))
			.andExpect(status().isCreated());
	}

	@Test
	void otherValidationFailuresNoLongerEchoTheRejectedInput() throws Exception {
		var result = mockMvc().perform(post("/api/ingestion/events")
				.contentType(MediaType.APPLICATION_JSON)
				.content(event(", \"eventId\": \"" + "x".repeat(101) + "\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("eventId"))
			.andExpect(jsonPath("$.errors[0].rejectedValue").value(nullValue()));

		assertThat(body(result)).doesNotContain("xxxxxxxxxx");
	}

	@Test
	void unknownBatchSchemaVersionRejectsTheWholeRequest() throws Exception {
		var result = mockMvc().perform(post("/api/ingestion/events/batch")
				.contentType(MediaType.APPLICATION_JSON)
				.content(batch(", \"schemaVersion\": \"nope\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("schemaVersion"));

		assertThat(body(result)).doesNotContain("SECRET-ITEM-TEXT");
		verifyNoInteractions(ingestionService);
	}

	@Test
	void batchItemMetadataIsJudgedPerItemByTheServiceNotByTheRequest() throws Exception {
		given(ingestionService.collectBatch(any(), any()))
			.willReturn(new IngestionBatchResponse(0, 1, List.of()));

		mockMvc().perform(post("/api/ingestion/events/batch")
				.contentType(MediaType.APPLICATION_JSON)
				.content(batch("")))
			.andExpect(status().isOk());
	}
}
