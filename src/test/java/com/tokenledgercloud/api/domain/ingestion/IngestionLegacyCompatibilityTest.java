package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.controller.IngestionController;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.IngestionService;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.GlobalExceptionHandler;

/**
 * Compatibility evidence for docs/api/ingestion-compat.md: requests exactly as an SDK from before #10 sends them
 * (no eventId, no schemaVersion, no Idempotency-Key) go through the real service and database, and the response
 * still carries every original field with its original meaning. Only project authentication is mocked.
 */
@SpringBootTest
class IngestionLegacyCompatibilityTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String API_KEY = "test-api-key";

	/** The single-event request from the original IngestionControllerTest, unchanged. */
	private static final String LEGACY_EVENT = """
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
		""";

	private static final String LEGACY_BATCH_ITEM = """
		{
		  "requestId": "%s",
		  "provider": "openai",
		  "model": "gpt-4o-mini",
		  "promptTokens": 1200,
		  "completionTokens": 400,
		  "totalTokens": 1600,
		  "totalCostUsd": 0.00042,
		  "pricingVersion": "2026-05-01",
		  "occurredAt": "2026-05-06T10:00:00Z"
		}""";

	@Autowired
	private IngestionService ingestionService;

	@Autowired
	private UsageLogRepository usageLogRepository;

	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(eq(API_KEY), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
		mockMvc = MockMvcBuilders.standaloneSetup(new IngestionController(ingestionService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@AfterEach
	void tearDown() {
		usageLogRepository.deleteAll();
	}

	private ResultActions send(String path, String body) throws Exception {
		return mockMvc.perform(post(path).header("X-API-Key", API_KEY)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String batch(String... items) {
		return "{\"projectKey\":\"support-copilot\",\"environment\":\"prod\",\"items\":[" + String.join(",", items) + "]}";
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new java.util.TreeSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private JsonNode json(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	// --- single event ---

	@Test
	void legacySingleEventStillReturns201WithTheOriginalFields() throws Exception {
		ResultActions result = send("/api/ingestion/events", LEGACY_EVENT)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("사용 이벤트 수집 성공"))
			.andExpect(jsonPath("$.data.eventId").value(notNullValue()))
			.andExpect(jsonPath("$.data.accepted").value(true));

		JsonNode body = json(result);
		assertThat(fieldNames(body)).containsExactly("code", "data", "errors", "message", "success", "timestamp");
		// the original fields plus exactly the two additions, nothing else
		assertThat(fieldNames(body.path("data"))).containsExactly("accepted", "duplicate", "eventId", "requestId");
		assertThat(body.path("data").path("duplicate").asBoolean()).isFalse();
		assertThat(body.path("data").path("requestId").asText()).isEqualTo("req_123");

		List<UsageLog> stored = usageLogRepository.findAll();
		assertThat(stored).singleElement().satisfies(log -> {
			assertThat(log.getId()).isEqualTo(body.path("data").path("eventId").asText());
			assertThat(log.getRequestId()).isEqualTo("req_123");
			assertThat(log.getEventId()).isNull();
			assertThat(log.getEnvironment()).isEqualTo("prod");
			assertThat(log.getPromptTokens()).isEqualTo(1200L);
			assertThat(log.getTotalCostUsd()).isEqualByComparingTo("0.00042");
		});
	}

	@Test
	void legacyRetransmissionStillSucceedsWithTheSameEventId() throws Exception {
		JsonNode first = json(send("/api/ingestion/events", LEGACY_EVENT).andExpect(status().isCreated()));

		ResultActions again = send("/api/ingestion/events", LEGACY_EVENT)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.accepted").value(true))
			.andExpect(jsonPath("$.data.duplicate").value(true));

		assertThat(json(again).path("data").path("eventId").asText())
			.isEqualTo(first.path("data").path("eventId").asText());
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void legacyValidationErrorKeepsItsShape() throws Exception {
		send("/api/ingestion/events", """
			{ "projectKey": "support-copilot", "environment": "prod", "provider": "openai" }
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON-400"));
		assertThat(usageLogRepository.count()).isZero();
	}

	/** The one intentional behaviour change: same key, different body is no longer silently accepted. */
	@Test
	void sameRequestIdWithADifferentBodyIsNowAConflictInsteadOfSilentlyAccepted() throws Exception {
		send("/api/ingestion/events", LEGACY_EVENT).andExpect(status().isCreated());

		send("/api/ingestion/events", LEGACY_EVENT.replace("\"promptTokens\": 1200", "\"promptTokens\": 9999"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("INGESTION-409"));
		assertThat(usageLogRepository.count()).isEqualTo(1);
		assertThat(usageLogRepository.findAll().get(0).getPromptTokens()).isEqualTo(1200L);
	}

	@Test
	void unknownTopLevelFieldsAreStillIgnoredAndNeverStored() throws Exception {
		send("/api/ingestion/events", LEGACY_EVENT.replace("\"requestId\": \"req_123\",",
			"\"requestId\": \"req_123\", \"prompt\": \"SECRET-PROMPT-TEXT\", \"somethingNew\": 1,"))
			.andExpect(status().isCreated());

		assertThat(usageLogRepository.findAll()).singleElement().satisfies(log -> {
			assertThat(String.valueOf(log.getMetadataJson())).doesNotContain("SECRET-PROMPT-TEXT");
			assertThat(log.toString()).doesNotContain("SECRET-PROMPT-TEXT");
		});
	}

	// --- batch ---

	@Test
	void legacyBatchStillReturns200WithTheOriginalFields() throws Exception {
		ResultActions result = send("/api/ingestion/events/batch",
			batch(LEGACY_BATCH_ITEM.formatted("req_a"), LEGACY_BATCH_ITEM.formatted("req_b")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.message").value("배치 수집 성공"))
			.andExpect(jsonPath("$.data.acceptedCount").value(2))
			.andExpect(jsonPath("$.data.rejectedCount").value(0))
			.andExpect(jsonPath("$.data.rejectedItems", hasSize(0)));

		JsonNode data = json(result).path("data");
		assertThat(fieldNames(data)).containsExactly(
			"acceptedCount", "createdCount", "duplicateCount", "items", "rejectedCount", "rejectedItems");
		assertThat(data.path("createdCount").asInt()).isEqualTo(2);
		assertThat(data.path("duplicateCount").asInt()).isZero();
		assertThat(data.path("items")).hasSize(2);
		assertThat(usageLogRepository.count()).isEqualTo(2);
	}

	@Test
	void legacyBatchRejectedItemsKeepTheirFieldsAndAddRetryable() throws Exception {
		String invalid = """
			{ "requestId": "req_bad", "provider": "openai", "model": "gpt-4o-mini",
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z" }""";

		ResultActions result = send("/api/ingestion/events/batch", batch(LEGACY_BATCH_ITEM.formatted("req_a"), invalid))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.acceptedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedCount").value(1))
			.andExpect(jsonPath("$.data.rejectedItems[0].index").value(1))
			.andExpect(jsonPath("$.data.rejectedItems[0].requestId").value("req_bad"))
			.andExpect(jsonPath("$.data.rejectedItems[0].code").value("COMMON-400"))
			.andExpect(jsonPath("$.data.rejectedItems[0].message").value(notNullValue()))
			.andExpect(jsonPath("$.data.rejectedItems[0].retryable").value(false));

		assertThat(fieldNames(json(result).path("data").path("rejectedItems").get(0)))
			.containsExactly("code", "index", "message", "requestId", "retryable");
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void legacyBatchResendKeepsCountingDuplicatesAsAccepted() throws Exception {
		String body = batch(LEGACY_BATCH_ITEM.formatted("req_a"), LEGACY_BATCH_ITEM.formatted("req_b"));
		send("/api/ingestion/events/batch", body).andExpect(status().isOk());

		send("/api/ingestion/events/batch", body)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.acceptedCount").value(2))
			.andExpect(jsonPath("$.data.rejectedCount").value(0))
			.andExpect(jsonPath("$.data.createdCount").value(0))
			.andExpect(jsonPath("$.data.duplicateCount").value(2));
		assertThat(usageLogRepository.count()).isEqualTo(2);
	}
}
