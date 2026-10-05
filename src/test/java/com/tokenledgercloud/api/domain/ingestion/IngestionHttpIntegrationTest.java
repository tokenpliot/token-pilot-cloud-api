package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.ingestion.web.IngestionRequestSizeFilter;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * Real HTTP against the running servlet container: the size filter is registered, runs ahead of Spring Security,
 * hands a normal body to the controller intact, and the limits and forbidden keys come from configuration.
 *
 * <p>Requests carry no {@code X-API-Key} on the happy path: the member-key filter currently answers 401 to any
 * key that is not a member key, project keys included (known issue, fixed on the #9 branch). Project
 * authentication itself is mocked here.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"token-pilot.ingestion.max-event-bytes=2048",
		"token-pilot.ingestion.max-batch-bytes=4096",
		"token-pilot.ingestion.forbidden-metadata-keys=prompt,secret_note"
	})
class IngestionHttpIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private UsageLogRepository usageLogRepository;

	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(isNull(), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(projectApiKeyAuthenticator);
		usageLogRepository.deleteAll();
	}

	private static String eventJson(String requestId, String extraFields) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod", "requestId": "%s",
			  "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			  "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z"%s }
			""".formatted(requestId, extraFields);
	}

	private static String metadata(int entries, int valueLength) {
		StringBuilder builder = new StringBuilder(", \"metadata\": {");
		for (int i = 0; i < entries; i++) {
			builder.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"").append("v".repeat(valueLength))
				.append('"');
		}
		return builder.append('}').toString();
	}

	private HttpResponse<String> send(String path, HttpRequest.BodyPublisher body, String... headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(body);
		if (headers.length > 0) {
			request.headers(headers);
		}
		return client.send(request.build(), BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String json, String... headers) throws Exception {
		return send(path, BodyPublishers.ofString(json), headers);
	}

	/** No declared length, so the JDK client sends it chunked. */
	private HttpResponse<String> postChunked(String path, String json) throws Exception {
		return send(path, BodyPublishers.fromPublisher(BodyPublishers.ofString(json)));
	}

	private JsonNode parse(HttpResponse<String> response) throws Exception {
		return JSON.readTree(response.body());
	}

	// --- the filter is really there, and in front of Spring Security ---

	@Test
	void sizeFilterIsARegisteredBeanAndRunsBeforeSpringSecurity() throws Exception {
		assertThat(applicationContext.getBeansOfType(IngestionRequestSizeFilter.class)).hasSize(1);

		// An unknown X-API-Key makes the member-key filter inside Spring Security answer 401 ...
		HttpResponse<String> normalSize = post("/api/ingestion/events", eventJson("req-order", ""),
			"X-API-Key", "not-a-member-key");
		assertThat(normalSize.statusCode()).isEqualTo(401);

		// ... so an oversized request getting 413 with that same header proves the size filter ran first.
		HttpResponse<String> oversized = post("/api/ingestion/events", eventJson("req-order", metadata(10, 250)),
			"X-API-Key", "not-a-member-key");
		assertThat(oversized.statusCode()).isEqualTo(413);
	}

	// --- a normal body arrives intact ---

	@Test
	void normalRequestBodyReachesTheControllerIntact() throws Exception {
		String json = eventJson("req-intact", ", \"eventId\": \"evt-intact\", \"schemaVersion\": \"2026-10-01\","
			+ " \"metadata\": {\"feature\": \"요약 기능\", \"team\": \"a\"}");

		HttpResponse<String> response = post("/api/ingestion/events", json);

		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode data = parse(response).path("data");
		assertThat(data.path("accepted").asBoolean()).isTrue();
		assertThat(data.path("duplicate").asBoolean()).isFalse();
		assertThat(data.path("requestId").asText()).isEqualTo("req-intact");

		List<UsageLog> stored = usageLogRepository.findAll();
		assertThat(stored).singleElement().satisfies(log -> {
			assertThat(log.getId()).isEqualTo(data.path("eventId").asText());
			assertThat(log.getRequestId()).isEqualTo("req-intact");
			assertThat(log.getEventId()).isEqualTo("evt-intact");
			assertThat(log.getProvider()).isEqualTo("openai");
			assertThat(log.getModel()).isEqualTo("gpt-4o-mini");
			assertThat(log.getPromptTokens()).isEqualTo(1200L);
			assertThat(log.getCompletionTokens()).isEqualTo(400L);
			assertThat(log.getTotalTokens()).isEqualTo(1600L);
			assertThat(log.getTotalCostUsd()).isEqualByComparingTo("0.00042");
			assertThat(log.getPricingVersion()).isEqualTo("2026-05-01");
			// H2 keeps a json column as an escaped JSON string; compare content, not quoting
			assertThat(log.getMetadataJson()).contains("요약 기능", "feature", "team");
		});
	}

	@Test
	void chunkedRequestWithinTheLimitAlsoArrivesIntact() throws Exception {
		HttpResponse<String> response = postChunked("/api/ingestion/events",
			eventJson("req-chunked", ", \"metadata\": {\"feature\": \"chunked 한글\"}"));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(usageLogRepository.findAll()).singleElement()
			.satisfies(log -> assertThat(log.getMetadataJson()).contains("chunked 한글"));
	}

	@Test
	void bodyJustUnderTheEventLimitIsAccepted() throws Exception {
		// about 1.3 KB of metadata: well-formed except for being long, so it is accepted
		HttpResponse<String> response = post("/api/ingestion/events", eventJson("req-under", metadata(5, 250)));

		assertThat(response.statusCode()).isEqualTo(201);
	}

	// --- oversized bodies ---

	@Test
	void oversizedEventWithDeclaredLengthIs413AndNothingIsProcessed() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events", eventJson("req-big", metadata(10, 250)));

		assertThat(response.statusCode()).isEqualTo(413);
		JsonNode body = parse(response);
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("code").asText()).isEqualTo("INGESTION-413");
		assertThat(response.body()).doesNotContain("vvvvvvvv");
		assertThat(usageLogRepository.count()).isZero();
		verify(projectApiKeyAuthenticator, never()).authenticate(any(), any(), any());
	}

	@Test
	void oversizedChunkedEventIs413() throws Exception {
		HttpResponse<String> response = postChunked("/api/ingestion/events",
			eventJson("req-big-chunked", metadata(10, 250)));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(parse(response).path("code").asText()).isEqualTo("INGESTION-413");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void batchEndpointHasItsOwnLargerLimit() throws Exception {
		String item = """
			{ "requestId": "%s", "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 10, "completionTokens": 5, "pricingVersion": "2026-05-01",
			  "occurredAt": "2026-05-06T10:00:00Z"%s }""";
		// ~3 KB: over the 2 KB event limit but under the 4 KB batch limit
		String within = "{\"projectKey\":\"support-copilot\",\"environment\":\"prod\",\"items\":["
			+ item.formatted("b-1", metadata(10, 250)) + "]}";
		String over = "{\"projectKey\":\"support-copilot\",\"environment\":\"prod\",\"items\":["
			+ item.formatted("b-2", metadata(10, 250)) + "," + item.formatted("b-3", metadata(10, 250)) + "]}";

		HttpResponse<String> accepted = post("/api/ingestion/events/batch", within);
		HttpResponse<String> rejected = post("/api/ingestion/events/batch", over);

		assertThat(accepted.statusCode()).isEqualTo(200);
		assertThat(parse(accepted).path("data").path("createdCount").asInt()).isEqualTo(1);
		assertThat(rejected.statusCode()).isEqualTo(413);
		assertThat(usageLogRepository.findAll()).extracting(UsageLog::getRequestId).containsExactly("b-1");
	}

	// --- configuration drives the forbidden keys, with the Spring-injected validator ---

	@Test
	void forbiddenKeyFromConfigurationIs400AndNothingIsStored() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-secret", ", \"metadata\": {\"secret_note\": \"CONFIDENTIAL-TEXT\"}"));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = parse(response);
		assertThat(body.path("code").asText()).isEqualTo("COMMON-400");
		assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("metadata");
		assertThat(body.path("errors").get(0).path("rejectedValue").isNull()).isTrue();
		assertThat(response.body()).doesNotContain("CONFIDENTIAL-TEXT");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void keyRemovedFromTheConfiguredListIsNowAllowed() throws Exception {
		// "content" is in the built-in list but this test's configuration replaces the list
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-content", ", \"metadata\": {\"content\": \"x\"}"));

		assertThat(response.statusCode()).isEqualTo(201);
	}

	@Test
	void unknownSchemaVersionIs400OverHttp() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-schema", ", \"schemaVersion\": \"2099-01-01\""));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).doesNotContain("2099-01-01");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void validationErrorsOverHttpDoNotEchoTheInput() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-echo", ", \"eventId\": \"" + "e".repeat(101) + "\""));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).doesNotContain("eeeeeeeeee");
	}
}
