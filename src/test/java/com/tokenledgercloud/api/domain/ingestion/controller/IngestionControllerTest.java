package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchItemResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionItemStatus;
import com.tokenledgercloud.api.domain.ingestion.dto.RejectedIngestionItemResponse;
import com.tokenledgercloud.api.domain.ingestion.service.IngestionService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.exception.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class IngestionControllerTest {

	@Mock
	private IngestionService ingestionService;

	@InjectMocks
	private IngestionController ingestionController;

	private MockMvc mockMvc() {
		return MockMvcBuilders.standaloneSetup(ingestionController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void collectEventReturnsWrappedSuccessResponse() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willReturn(new IngestionEventResponse("evt_ing_001", true));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "projectKey": "support-copilot",
					  "environment": "prod",
					  "requestId": "req_123",
					  "provider": "openai",
					  "model": "gpt-4o-mini",
					  "promptTokens": 1200,
					  "completionTokens": 400,
					  "reasoningTokens": 0,
					  "totalTokens": 1600,
					  "promptCostUsd": 0.00018,
					  "completionCostUsd": 0.00024,
					  "reasoningCostUsd": 0,
					  "totalCostUsd": 0.00042,
					  "pricingVersion": "2026-05-01",
					  "occurredAt": "2026-05-06T10:00:00Z",
					  "metadata": {
					    "tenantId": "tenant-a"
					  }
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.message").value("사용 이벤트 수집 성공"))
			.andExpect(jsonPath("$.data.eventId").value("evt_ing_001"))
			.andExpect(jsonPath("$.data.accepted").value(true));
	}

	@Test
	void collectEventReturnsValidationErrorResponse() throws Exception {
		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "projectKey": "support-copilot",
					  "environment": "prod",
					  "provider": "openai"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@Test
	void collectBatchReturnsWrappedSuccessResponse() throws Exception {
		given(ingestionService.collectBatch(eq("test-api-key"), any()))
			.willReturn(new IngestionBatchResponse(
				1,
				1,
				List.of(new RejectedIngestionItemResponse(1, "req_bad", "COMMON-400", "Invalid request input."))
			));

		mockMvc().perform(post("/api/ingestion/events/batch")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "projectKey": "support-copilot",
					  "environment": "prod",
					  "items": [
					    {
					      "requestId": "req_123",
					      "provider": "openai",
					      "model": "gpt-4o-mini",
					      "promptTokens": 1200,
					      "completionTokens": 400,
					      "totalTokens": 1600,
					      "totalCostUsd": 0.00042,
					      "pricingVersion": "2026-05-01",
					      "occurredAt": "2026-05-06T10:00:00Z"
					    }
					  ]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.message").value("배치 수집 성공"))
			.andExpect(jsonPath("$.data.acceptedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedItems[0].requestId").value("req_bad"));
	}

	@Test
	void collectEventKeepsLegacyFieldsAndAddsDuplicateAndRequestId() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willReturn(new IngestionEventResponse("evt_ing_001", true, true, "req_123"));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted("")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.eventId").value("evt_ing_001"))
			.andExpect(jsonPath("$.data.accepted").value(true))
			.andExpect(jsonPath("$.data.duplicate").value(true))
			.andExpect(jsonPath("$.data.requestId").value("req_123"));
	}

	@Test
	void collectEventBindsOptionalEventId() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willReturn(new IngestionEventResponse("evt_ing_001", true));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted(", \"eventId\": \"evt-42\"")))
			.andExpect(status().isCreated());

		ArgumentCaptor<IngestionEventRequest> captor = ArgumentCaptor.forClass(IngestionEventRequest.class);
		verify(ingestionService).collectEvent(eq("test-api-key"), captor.capture());
		assertThat(captor.getValue().eventId()).isEqualTo("evt-42");
	}

	@Test
	void collectEventWithoutEventIdBindsNull() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willReturn(new IngestionEventResponse("evt_ing_001", true));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted("")))
			.andExpect(status().isCreated());

		ArgumentCaptor<IngestionEventRequest> captor = ArgumentCaptor.forClass(IngestionEventRequest.class);
		verify(ingestionService).collectEvent(eq("test-api-key"), captor.capture());
		assertThat(captor.getValue().eventId()).isNull();
		assertThat(captor.getValue().requestId()).isEqualTo("req_123");
	}

	@Test
	void collectEventRejectsTooLongEventId() throws Exception {
		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted(", \"eventId\": \"" + "x".repeat(101) + "\"")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"));
	}

	@Test
	void idempotencyConflictIsReturnedAs409() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willThrow(new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted("")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("INGESTION-409"));
	}

	@Test
	void unresolvedConcurrentRequestIsReturnedAs503() throws Exception {
		given(ingestionService.collectEvent(eq("test-api-key"), any()))
			.willThrow(new ApiException(ErrorCode.INGESTION_RETRY_LATER));

		mockMvc().perform(post("/api/ingestion/events")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content(EVENT_JSON.formatted("")))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("INGESTION-503"));
	}

	@Test
	void collectBatchKeepsLegacyFieldsAndAddsItemResults() throws Exception {
		given(ingestionService.collectBatch(eq("test-api-key"), any()))
			.willReturn(new IngestionBatchResponse(
				1, 1,
				List.of(new RejectedIngestionItemResponse(1, "req_b", "INGESTION-503", "retry", true)),
				0, 1,
				List.of(
					new IngestionBatchItemResponse(0, "req_a", IngestionItemStatus.DUPLICATE, "usage-1", null, null, false),
					new IngestionBatchItemResponse(1, "req_b", IngestionItemStatus.REJECTED, null, "INGESTION-503", "retry", true))
			));

		mockMvc().perform(post("/api/ingestion/events/batch")
				.header("X-API-Key", "test-api-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"projectKey":"support-copilot","environment":"prod","items":[
					  {"requestId":"req_a","provider":"openai","model":"m","promptTokens":1,"completionTokens":1,
					   "pricingVersion":"v","occurredAt":"2026-05-06T10:00:00Z"}]}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.acceptedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedItems[0].index").value(1))
			.andExpect(jsonPath("$.data.rejectedItems[0].requestId").value("req_b"))
			.andExpect(jsonPath("$.data.rejectedItems[0].code").value("INGESTION-503"))
			.andExpect(jsonPath("$.data.rejectedItems[0].message").value("retry"))
			.andExpect(jsonPath("$.data.rejectedItems[0].retryable").value(true))
			.andExpect(jsonPath("$.data.createdCount").value(0))
			.andExpect(jsonPath("$.data.duplicateCount").value(1))
			.andExpect(jsonPath("$.data.items[0].status").value("DUPLICATE"))
			.andExpect(jsonPath("$.data.items[0].usageEventId").value("usage-1"))
			.andExpect(jsonPath("$.data.items[1].status").value("REJECTED"))
			.andExpect(jsonPath("$.data.items[1].retryable").value(true));
	}

	private static final String EVENT_JSON = """
		{
		  "projectKey": "support-copilot",
		  "environment": "prod",
		  "requestId": "req_123",
		  "provider": "openai",
		  "model": "gpt-4o-mini",
		  "promptTokens": 1200,
		  "completionTokens": 400,
		  "totalTokens": 1600,
		  "totalCostUsd": 0.00042,
		  "pricingVersion": "2026-05-01",
		  "occurredAt": "2026-05-06T10:00:00Z"%s
		}
		""";
}
