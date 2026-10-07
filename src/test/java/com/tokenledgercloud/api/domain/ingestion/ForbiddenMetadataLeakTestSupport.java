package com.tokenledgercloud.api.domain.ingestion;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Shared plumbing for the "forbidden metadata keys never leak" tests: real HTTP against the running application,
 * the real validator and service, H2 for storage, and a capture of every log line.
 *
 * <p>The forbidden keys are configured as {@code leaky_key} and {@code leaky_nested} so the assertions can search the
 * database, the logs and the responses for those names without hitting unrelated words such as "prompt_tokens".
 */
abstract class ForbiddenMetadataLeakTestSupport {

	static final String FORBIDDEN_KEYS = "token-pilot.ingestion.forbidden-metadata-keys=leaky_key,leaky_nested";
	static final String SECRET_VALUE = "SECRET-RAW-TEXT-123";
	static final ObjectMapper JSON = new ObjectMapper();

	@Value("${local.server.port}")
	int port;

	@Autowired
	UsageLogRepository usageLogRepository;

	@MockitoBean
	ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private Logger rootLogger;

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(isNull(), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		usageLogRepository.deleteAll();
		rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		logs.list.clear();
		logs.start();
		rootLogger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		rootLogger.detachAppender(logs);
		Mockito.reset(projectApiKeyAuthenticator);
		usageLogRepository.deleteAll();
	}

	static String eventJson(String requestId, String metadata) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod", "requestId": "%s",
			  "provider": "openai", "model": "gpt-4o-mini",
			  "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			  "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z", "metadata": %s }
			""".formatted(requestId, metadata);
	}

	static String batchJson(String requestId, String metadata) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod",
			  "items": [ { "requestId": "%s", "provider": "openai", "model": "gpt-4o-mini",
			    "promptTokens": 1200, "completionTokens": 400, "totalTokens": 1600,
			    "promptCostUsd": 0.00018, "completionCostUsd": 0.00024, "totalCostUsd": 0.00042,
			    "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z", "metadata": %s } ] }
			""".formatted(requestId, metadata);
	}

	/** Top-level, differently cased, and nested inside a map and a list. */
	static String leakyMetadata() {
		return """
			{ "feature": "summary", "Leaky_Key": "%s",
			  "ctx": { "leaky_nested": "%s", "team": "support" },
			  "turns": [ { "LEAKY_KEY": "%s", "role": "user" } ] }
			""".formatted(SECRET_VALUE, SECRET_VALUE, SECRET_VALUE);
	}

	HttpResponse<String> post(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(json))
			.build();
		return client.send(request, BodyHandlers.ofString());
	}

	static JsonNode parse(HttpResponse<String> response) throws Exception {
		return JSON.readTree(response.body());
	}

	/** Every captured log line, with its throwable, as one lower-cased string. */
	String capturedLogs() {
		StringBuilder all = new StringBuilder();
		for (ILoggingEvent entry : logs.list) {
			all.append(entry.getFormattedMessage()).append('\n');
			if (entry.getThrowableProxy() != null) {
				all.append(entry.getThrowableProxy().getMessage()).append('\n');
			}
		}
		return all.toString().toLowerCase();
	}

	String storedMetadata(String requestId) {
		return usageLogRepository.findAll().stream()
			.filter(row -> requestId.equals(row.getRequestId())).findFirst().orElseThrow().getMetadataJson();
	}
}
