package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * Field lengths follow the usage_events columns: over-long input is a 400 (a REJECTED item in a batch) instead of a
 * database error and a 500 after earlier items were already stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionFieldLengthTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Value("${local.server.port}")
	private int port;

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
		given(projectApiKeyAuthenticator.authenticate(any(), eq("support-copilot"), any()))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(projectApiKeyAuthenticator);
		usageLogRepository.deleteAll();
	}

	/** field name, column length */
	static Stream<Arguments> itemFields() {
		return Stream.of(
			Arguments.of("requestId", 100),
			Arguments.of("provider", 50),
			Arguments.of("model", 100),
			Arguments.of("pricingPlanId", 36),
			Arguments.of("pricingVersion", 50),
			Arguments.of("sourceType", 30),
			Arguments.of("eventId", 100));
	}

	static Stream<Arguments> singleEventFields() {
		return Stream.concat(itemFields(), Stream.of(Arguments.of("environment", 20)));
	}

	@ParameterizedTest(name = "single event: {0} longer than {1} is 400")
	@MethodSource("singleEventFields")
	void singleEventOverTheColumnLengthIsBadRequest(String field, int max) throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events", event(field, "a".repeat(max + 1)));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("code").asText()).isEqualTo("COMMON-400");
		assertThat(body.path("errors").findValuesAsText("field")).contains(field);
		assertThat(usageLogRepository.count()).isZero();
	}

	@ParameterizedTest(name = "single event: {0} of exactly {1} is accepted")
	@MethodSource("singleEventFields")
	void singleEventAtTheColumnLengthIsAccepted(String field, int max) throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events", event(field, "a".repeat(max)));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@ParameterizedTest(name = "batch: item with {0} longer than {1} is REJECTED, the others are stored")
	@MethodSource("itemFields")
	void batchRejectsOnlyTheItemOverTheColumnLength(String field, int max) throws Exception {
		String good1 = item("batch-good-1", null, null);
		String bad = item("batch-bad", field, "a".repeat(max + 1));
		String good2 = item("batch-good-2", null, null);

		HttpResponse<String> response = post("/api/ingestion/events/batch", batch("prod", good1, bad, good2));

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode data = JSON.readTree(response.body()).path("data");
		assertThat(data.path("acceptedCount").asInt()).isEqualTo(2);
		assertThat(data.path("rejectedCount").asInt()).isEqualTo(1);
		assertThat(data.path("items").get(0).path("status").asText()).isEqualTo("CREATED");
		JsonNode rejected = data.path("items").get(1);
		assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
		assertThat(rejected.path("code").asText()).isEqualTo("COMMON-400");
		assertThat(rejected.path("retryable").asBoolean()).isFalse();
		assertThat(rejected.path("message").asText()).contains(field);
		assertThat(data.path("items").get(2).path("status").asText()).isEqualTo("CREATED");
		assertThat(usageLogRepository.count()).isEqualTo(2);
	}

	@Test
	void batchEnvironmentOverTheColumnLengthRejectsTheWholeRequest() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events/batch",
			batch("e".repeat(21), item("batch-env-1", null, null), item("batch-env-2", null, null)));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(JSON.readTree(response.body()).path("errors").findValuesAsText("field")).contains("environment");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void internalUsageLogEndpointRejectsOverLongFieldsWithBadRequest() throws Exception {
		assertThat(post("/internal/usage-logs", internal("provider", "a".repeat(51))).statusCode()).isEqualTo(400);
		assertThat(post("/internal/usage-logs", internal("environment", "a".repeat(21))).statusCode()).isEqualTo(400);
		assertThat(post("/internal/usage-logs", internal("requestId", "a".repeat(101))).statusCode()).isEqualTo(400);
		assertThat(post("/internal/usage-logs", internal("projectId", "a".repeat(37))).statusCode()).isEqualTo(400);
		assertThat(usageLogRepository.count()).isZero();

		assertThat(post("/internal/usage-logs", internal("provider", "a".repeat(50))).statusCode()).isEqualTo(201);
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	private static String field(String name, String value) {
		return ", \"" + name + "\": \"" + value + "\"";
	}

	/** Item JSON; {@code overrideField} replaces a default value, or is added when it has none. */
	private static String item(String requestId, String overrideField, String overrideValue) {
		String requestIdValue = "requestId".equals(overrideField) ? overrideValue : requestId;
		String provider = "provider".equals(overrideField) ? overrideValue : "openai";
		String model = "model".equals(overrideField) ? overrideValue : "gpt-4o-mini";
		String pricingVersion = "pricingVersion".equals(overrideField) ? overrideValue : "2026-05-01";
		String extra = "";
		for (String optional : new String[] {"pricingPlanId", "sourceType", "eventId"}) {
			if (optional.equals(overrideField)) {
				extra += field(optional, overrideValue);
			}
		}
		return """
			{ "requestId": "%s", "provider": "%s", "model": "%s",
			  "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			  "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			  "pricingVersion": "%s", "occurredAt": "2026-05-06T10:00:00Z"%s }
			""".formatted(requestIdValue, provider, model, pricingVersion, extra);
	}

	private static String event(String overrideField, String overrideValue) {
		String environment = "environment".equals(overrideField) ? overrideValue : "prod";
		String item = item("single-1", "environment".equals(overrideField) ? null : overrideField, overrideValue);
		return item.replaceFirst("\\{",
			"{ \"projectKey\": \"support-copilot\", \"environment\": \"" + environment + "\",");
	}

	private static String batch(String environment, String... items) {
		return "{ \"projectKey\": \"support-copilot\", \"environment\": \"" + environment + "\", \"items\": ["
			+ String.join(",", items) + "] }";
	}

	private static String internal(String overrideField, String overrideValue) {
		String organizationId = "organizationId".equals(overrideField) ? overrideValue : "org-1";
		String projectId = "projectId".equals(overrideField) ? overrideValue : "project-1";
		String environment = "environment".equals(overrideField) ? overrideValue : "prod";
		String requestId = "requestId".equals(overrideField) ? overrideValue : "internal-1";
		String provider = "provider".equals(overrideField) ? overrideValue : "openai";
		return """
			{ "organizationId": "%s", "projectId": "%s", "environment": "%s", "requestId": "%s",
			  "provider": "%s", "model": "gpt-4o-mini",
			  "promptTokens": 10, "completionTokens": 5,
			  "promptCostUsd": 0.0001, "completionCostUsd": 0.0001,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00" }
			""".formatted(organizationId, projectId, environment, requestId, provider);
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
