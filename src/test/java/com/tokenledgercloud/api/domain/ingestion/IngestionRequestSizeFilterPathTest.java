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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * The size limit must apply to the path Spring MVC actually routes to, not to the raw request URI: a percent-encoded
 * or path-parameter spelling of an ingestion endpoint reaches the same controller and must get the same 413.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"token-pilot.ingestion.max-event-bytes=2048",
		"token-pilot.ingestion.max-batch-bytes=4096"
	})
class IngestionRequestSizeFilterPathTest {

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
		given(projectApiKeyAuthenticator.authenticate(any(), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(projectApiKeyAuthenticator);
		usageLogRepository.deleteAll();
	}

	@Test
	void percentEncodedEventPathIsStillLimited() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/even%74s", eventJson("req-enc", padding(3000)));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void percentEncodedBatchPathIsStillLimitedWithTheBatchLimit() throws Exception {
		String item = eventItem("req-batch", padding(3000));
		String oversized = batch(item + "," + item);
		assertThat(oversized.length()).isGreaterThan(4096);

		assertThat(post("/api/ingestion/events/%62atch", oversized).statusCode()).isEqualTo(413);
		// 3000+ bytes is over the event limit but under the batch limit: the batch limit must be the one applied
		assertThat(post("/api/ingestion/events/%62atch", batch(item)).statusCode()).isEqualTo(200);
	}

	@Test
	void pathParameterSpellingIsStillLimited() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events;v=1", eventJson("req-param", padding(3000)));

		assertThat(response.statusCode()).isEqualTo(413);
	}

	@Test
	void encodedPathWithNormalSizeStillReachesTheController() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/even%74s", eventJson("req-ok", ""));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	private static String padding(int length) {
		return ", \"metadata\": {\"note\": \"" + "x".repeat(length) + "\"}";
	}

	private static String eventItem(String requestId, String extraFields) {
		return """
			{ "requestId": "%s", "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			  "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z"%s }
			""".formatted(requestId, extraFields);
	}

	private static String eventJson(String requestId, String extraFields) {
		return eventItem(requestId, extraFields)
			.replaceFirst("\\{", "{ \"projectKey\": \"support-copilot\", \"environment\": \"prod\",");
	}

	private static String batch(String items) {
		return "{ \"projectKey\": \"support-copilot\", \"environment\": \"prod\", \"items\": [" + items + "] }";
	}

	private HttpResponse<String> post(String rawPath, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
