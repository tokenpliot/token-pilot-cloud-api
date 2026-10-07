package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures.SeededKey;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * What a client actually gets, over real HTTP and through the real filter chain, when authentication and request
 * validation both have something to say. Project authentication is real here (no mocks).
 *
 * <p>Order: size filter, then Spring Security (the member-key filter skips the two ingestion POST paths), then
 * request validation (schemaVersion, forbidden metadata keys), then the project-key check in the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionAuthOrderHttpTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String PATH = "/api/ingestion/events";

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectApiKeyRepository projectApiKeyRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UsageLogRepository usageLogRepository;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private ProjectKeyFixtures fixtures;
	private SeededKey validProjectKey;

	@BeforeEach
	void setUp() {
		usageLogRepository.deleteAll();
		fixtures = new ProjectKeyFixtures(projectRepository, projectApiKeyRepository, passwordEncoder);
		Project project = fixtures.project(ProjectKeyFixtures.PROJECT_KEY, ProjectStatus.ACTIVE);
		validProjectKey = fixtures.key(project, "prod", "ACTIVE", null);
	}

	@AfterEach
	void tearDown() {
		usageLogRepository.deleteAll();
		fixtures.clear();
	}

	private static String event(String extraFields) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod", "requestId": "req-1",
			  "provider": "openai", "model": "gpt-4o-mini", "promptTokens": 10, "completionTokens": 5,
			  "pricingVersion": "2026-05-01", "occurredAt": "2026-05-06T10:00:00Z"%s }
			""".formatted(extraFields);
	}

	private HttpResponse<String> post(String json, String apiKey) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(json));
		if (apiKey != null) {
			request.header("X-API-Key", apiKey);
		}
		return client.send(request.build(), BodyHandlers.ofString());
	}

	// --- no X-API-Key header: validation runs before the service's authentication ---

	@Test
	void missingKeyWithAnUnknownSchemaVersionIs400BecauseValidationComesFirst() throws Exception {
		HttpResponse<String> response = post(event(", \"schemaVersion\": \"2099-01-01\""), null);

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("code").asText()).isEqualTo("COMMON-400");
		assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("schemaVersion");
		assertThat(response.body()).doesNotContain("2099-01-01");
	}

	@Test
	void missingKeyWithAForbiddenMetadataKeyIs401InDefaultModeBecauseTheKeyIsNotAValidationError() throws Exception {
		HttpResponse<String> response = post(event(", \"metadata\": {\"prompt\": \"SECRET-TEXT\"}"), null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).doesNotContain("SECRET-TEXT");
	}

	@Test
	void missingKeyWithAValidBodyIs401FromTheService() throws Exception {
		HttpResponse<String> response = post(event(""), null);

		assertThat(response.statusCode()).isEqualTo(401);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("code").asText()).isEqualTo("COMMON-401");
		assertThat(body.path("message").asText()).isEqualTo("Project API key is required.");
		assertThat(usageLogRepository.count()).isZero();
	}

	// --- with an X-API-Key header: the member-key filter skips ingestion, so the order is the same ---

	@Test
	void unknownKeyWithAnUnknownSchemaVersionIs400BecauseValidationComesFirst() throws Exception {
		HttpResponse<String> response = post(event(", \"schemaVersion\": \"2099-01-01\""), "tpk_live_" + "0".repeat(36));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("code").asText()).isEqualTo("COMMON-400");
		assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("schemaVersion");
		assertThat(response.body()).doesNotContain("2099-01-01");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void unknownKeyWithAValidBodyIs401FromTheService() throws Exception {
		HttpResponse<String> response = post(event(""), "tpk_live_" + "0".repeat(36));

		assertThat(response.statusCode()).isEqualTo(401);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("code").asText()).isEqualTo("COMMON-401");
		assertThat(body.path("message").asText()).isEqualTo("Invalid project API key.");
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void validProjectKeyIsAcceptedOverHttp() throws Exception {
		HttpResponse<String> response = post(event(""), validProjectKey.raw());

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void validProjectKeyWithAnUnknownSchemaVersionIs400() throws Exception {
		HttpResponse<String> response = post(event(", \"schemaVersion\": \"2099-01-01\""), validProjectKey.raw());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(usageLogRepository.count()).isZero();
	}
}
