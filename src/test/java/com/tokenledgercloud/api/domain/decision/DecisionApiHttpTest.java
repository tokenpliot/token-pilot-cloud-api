package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.policy.PolicyRequests;
import com.tokenledgercloud.api.domain.policy.PolicyRules;
import com.tokenledgercloud.api.domain.policy.PolicyService;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * 실제 서버를 띄워 POST /api/control/v1/decisions를 호출한다. 응답은 control-plane-v1.yaml의 스키마로 검증하고,
 * 경로 표기(끝 슬래시, 세미콜론, 퍼센트 인코딩)가 달라도 필터가 같은 방식으로 처리하는지 확인한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:decision-http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.decision.enabled=true",
		"token-pilot.observe.ingestion.rate-unit-tokens=1000000",
		"token-pilot.ingestion.max-event-bytes=2048"
	})
class DecisionApiHttpTest {

	private static final String PATH = "/api/control/v1/decisions";
	private static final String MODEL = "gpt-priced";

	@Value("${local.server.port}") int port;
	@Autowired PolicyService policies;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository apiKeys;
	@Autowired PricingPlanRepository pricingPlans;
	@Autowired MemberRepository members;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtTokenProvider jwt;
	@Autowired JdbcTemplate jdbc;

	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10)).build();

	ProjectKeyFixtures fixtures;
	Project project;
	String key;
	Authentication admin;

	@BeforeEach
	void setUp() {
		clean();
		fixtures = new ProjectKeyFixtures(projects, apiKeys, passwordEncoder);
		project = fixtures.project("alpha", ProjectStatus.ACTIVE);
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId(ProjectKeyFixtures.ORG)
			.projectId(project.getId()).environment("prod").build());
		key = fixtures.key(project, null, "ACTIVE", null).raw();
		members.saveAndFlush(Member.builder().id("admin").email("admin@example.test").name("admin").provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
			"m-admin", ProjectKeyFixtures.ORG, "admin", "ADMIN", LocalDateTime.now(), LocalDateTime.now());
		admin = jwt.getAuthentication(jwt.createToken("admin@example.test", "USER"));
		pricingPlans.saveAndFlush(PricingPlan.builder().id("plan-1").catalogId("catalog-1").provider("openai").model(MODEL)
			.currency("USD").promptRate(new BigDecimal("2")).completionRate(new BigDecimal("8")).version("prices-v1")
			.effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
	}

	@AfterEach
	void clean() {
		for (String table : List.of("decision_records", "policy_request_contexts", "policy_snapshots", "customer_policies",
			"monthly_budget_settings", "pricing_snapshots", "pricing_plans", "organization_members", "project_api_keys",
			"project_environments", "projects", "users")) {
			jdbc.update("delete from " + table);
		}
	}

	private void policy(String hard, String requestHard) {
		policies.replace(admin, project.getId(), null, new PolicyRequests.Replace(0L, new PolicyRules("USD", null,
			new BigDecimal(hard), null, requestHard == null ? null : new BigDecimal(requestHard), true, List.of()), "test policy"));
	}

	private long records() {
		return jdbc.queryForObject("select count(*) from decision_records", Long.class);
	}

	// ---- 판정 결과는 모두 200 ----

	@Test
	void allowIsOkAndMatchesTheContractSchema() throws Exception {
		policy("100", null);

		HttpResponse<String> response = post(PATH, key, "dec:req-1", body("req-1", ""));

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode json = ControlPlaneSpec.assertValid("DecisionEnvelope", response.body());
		assertThat(json.at("/code").asText()).isEqualTo("SUCCESS");
		assertThat(json.at("/data/outcome").asText()).isEqualTo("ALLOW");
		assertThat(json.at("/data/requestId").asText()).isEqualTo("req-1");
		assertThat(json.at("/data/requiredCostUsd").asText()).isEqualTo("0.006000");
	}

	@Test
	void denyAndIndeterminateAreAlsoOk() throws Exception {
		policy("100", "0.001");
		HttpResponse<String> deny = post(PATH, key, "dec:req-deny", body("req-deny", ""));
		HttpResponse<String> indeterminate = post(PATH, key, "dec:req-ind", body("req-ind", "").replace(MODEL, "model-without-plan"));

		assertThat(deny.statusCode()).isEqualTo(200);
		assertThat(ControlPlaneSpec.assertValid("DecisionEnvelope", deny.body()).at("/data/outcome").asText()).isEqualTo("DENY_BUDGET");
		assertThat(indeterminate.statusCode()).isEqualTo(200);
		JsonNode json = ControlPlaneSpec.assertValid("DecisionEnvelope", indeterminate.body());
		assertThat(json.at("/data/outcome").asText()).isEqualTo("INDETERMINATE");
		assertThat(json.at("/data/reasons/0/code").asText()).isEqualTo("PRICING_NOT_FOUND");
	}

	@Test
	void sameKeyAndBodyReturnTheSameData() throws Exception {
		policy("100", null);

		JsonNode first = ControlPlaneSpec.assertValid("DecisionEnvelope", post(PATH, key, "dec:req-1", body("req-1", "")).body());
		JsonNode again = ControlPlaneSpec.assertValid("DecisionEnvelope", post(PATH, key, "dec:req-1", body("req-1", "")).body());

		assertThat(again.at("/data")).isEqualTo(first.at("/data"));
		assertThat(records()).isEqualTo(1);
	}

	// ---- 오류 응답 ----

	@Test
	void unknownFieldIsRejectedAndTheTextIsNeverEchoedOrStored() throws Exception {
		policy("100", null);
		String marker = "customer-secret-marker-9f2c";

		HttpResponse<String> response = post(PATH, key, "dec:req-1", body("req-1", ", \"prompt\": \"" + marker + "\""));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
		assertThat(json.at("/code").asText()).isEqualTo("INVALID_REQUEST");
		assertThat(json.at("/data/outcome").asText()).isEqualTo("INDETERMINATE");
		assertThat(json.at("/errors/0/field").asText()).isEqualTo("prompt");
		assertThat(response.body()).doesNotContain(marker);
		assertThat(records()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from policy_request_contexts", Long.class)).isZero();
	}

	@Test
	void contractViolationsAreInvalidRequestWithFieldErrors() throws Exception {
		HttpResponse<String> response = post(PATH, key, "dec:req-1", body("req-1", "").replace("\"ADVISORY\"", "\"ADVISORY\", \"maxOutputTokens\": -5")
			.replace("\"2026-10-01\"", "\"1999-01-01\""));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
		assertThat(json.at("/errors").findValuesAsText("field")).contains("schemaVersion");
	}

	@Test
	void brokenJsonAndUnknownEnumAreInvalidRequest() throws Exception {
		assertThat(post(PATH, key, "dec:req-1", "{ not json").statusCode()).isEqualTo(400);
		HttpResponse<String> badEnum = post(PATH, key, "dec:req-1", body("req-1", "").replace("\"ADVISORY\"", "\"BLOCK_ALL\""));

		assertThat(badEnum.statusCode()).isEqualTo(400);
		ControlPlaneSpec.assertValid("ErrorEnvelope", badEnum.body());
	}

	@Test
	void idempotencyKeyMustBeEightTo128Characters() throws Exception {
		for (String badKey : new String[] {null, "short-7", "k".repeat(129)}) {
			HttpResponse<String> response = post(PATH, key, badKey, body("req-1", ""));

			assertThat(response.statusCode()).as("key=%s", badKey == null ? null : badKey.length()).isEqualTo(400);
			JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
			assertThat(json.at("/errors/0/field").asText()).isEqualTo("Idempotency-Key");
		}
		assertThat(post(PATH, key, "k".repeat(8), body("req-8", "")).statusCode()).isEqualTo(200);
		assertThat(post(PATH, key, "k".repeat(127) + "x", body("req-128", "")).statusCode()).isEqualTo(200);
		assertThat(records()).isEqualTo(2);
	}

	@Test
	void missingOrWrongProjectKeyIsUnauthorized() throws Exception {
		HttpResponse<String> missing = post(PATH, null, "dec:req-1", body("req-1", ""));
		HttpResponse<String> wrong = post(PATH, "tpk_live_not-a-real-key-000000000000", "dec:req-1", body("req-1", ""));

		for (HttpResponse<String> response : List.of(missing, wrong)) {
			assertThat(response.statusCode()).isEqualTo(401);
			JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
			assertThat(json.at("/code").asText()).isEqualTo("UNAUTHORIZED");
			assertThat(json.at("/data/outcome").asText()).isEqualTo("INDETERMINATE");
		}
	}

	@Test
	void keyOfAnotherEnvironmentIsForbiddenNotADenial() throws Exception {
		String devOnly = fixtures.key(project, "dev", "ACTIVE", null).raw();

		HttpResponse<String> response = post(PATH, devOnly, "dec:req-1", body("req-1", ""));

		assertThat(response.statusCode()).isEqualTo(403);
		JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
		assertThat(json.at("/data/outcome").asText()).isEqualTo("INDETERMINATE");
	}

	@Test
	void sameKeyWithAnotherBodyIsConflict() throws Exception {
		policy("100", null);
		post(PATH, key, "dec:req-1", body("req-1", ""));

		HttpResponse<String> response = post(PATH, key, "dec:req-1", body("req-1", "").replace("1000", "2000"));

		assertThat(response.statusCode()).isEqualTo(409);
		JsonNode json = ControlPlaneSpec.assertValid("ErrorEnvelope", response.body());
		assertThat(json.at("/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(json.at("/data/outcome").asText()).isEqualTo("INDETERMINATE");
	}

	@Test
	void wrongMethodAndContentTypeAreClientErrorsNotServerErrors() throws Exception {
		// X-API-Key 없이 보낸다: 회원 키 필터는 수집 경로와 마찬가지로 POST만 건너뛰므로, 프로젝트 키가 붙은 GET은 401이다.
		HttpResponse<String> get = send("GET", PATH, null, null, null, "application/json");
		HttpResponse<String> text = send("POST", PATH, key, "dec:req-1", body("req-1", ""), "text/plain");

		assertThat(get.statusCode()).isEqualTo(405);
		assertThat(text.statusCode()).isEqualTo(415);
	}

	// ---- 경로 표기가 달라도 같은 필터 처리를 받는다 ----

	@Test
	void oversizedBodyIsRefusedOnEverySpellingOfThePath() throws Exception {
		String oversized = body("req-big", ", \"note\": \"" + "x".repeat(3000) + "\"");
		assertThat(oversized.length()).isGreaterThan(2048);

		for (String path : List.of(PATH, PATH + "/", PATH + ";v=1", "/api/control/v1/%64ecisions", "/api/control//v1/decisions")) {
			HttpResponse<String> response = post(path, key, "dec:req-big", oversized);

			assertThat(response.statusCode()).as(path).isEqualTo(413);
		}
		assertThat(records()).isZero();
	}

	@Test
	void projectKeyIsNotInterceptedByTheMemberKeyFilterOnEverySpellingOfThePath() throws Exception {
		policy("100", null);

		int n = 0;
		for (String path : List.of(PATH, "/api/control/v1/%64ecisions")) {
			HttpResponse<String> response = post(path, key, "dec:req-" + (++n) + "-x", body("req-" + n, ""));

			assertThat(response.statusCode()).as(path).isEqualTo(200);
		}
		// 끝 슬래시는 Spring MVC가 컨트롤러에 연결하지 않아 404다. 회원 키 필터의 401이 아니라는 점이 중요하다.
		HttpResponse<String> trailing = post(PATH + "/", key, "dec:req-slash", body("req-slash", ""));
		assertThat(trailing.statusCode()).isEqualTo(404);
		assertThat(records()).isEqualTo(2);
	}

	@Test
	void semicolonAndDoubleSlashSpellingsAreRejectedByTheFirewallJustLikeIngestionAndEveryOtherPath() throws Exception {
		policy("100", null);

		// Spring Security의 기본 방화벽이 ';'나 '//'가 든 경로를 필터 체인 앞에서 400으로 거부한다(/api/ingestion/events;v=1도 같다).
		// 컨트롤러에 닿지 않으므로 저장되지 않고, 회원 키 필터의 401도 아니다. 크기 초과는 위 테스트처럼 필터가 먼저 413으로 거부한다.
		for (String path : List.of(PATH + ";v=1", "/api/control//v1/decisions")) {
			HttpResponse<String> response = post(path, key, "dec:req-semi", body("req-semi", ""));

			assertThat(response.statusCode()).as(path).isEqualTo(400);
		}
		assertThat(records()).isZero();
	}

	// ---- helpers ----

	private static String body(String requestId, String extraFields) {
		return """
			{"schemaVersion":"2026-10-01","requestId":"%s","environment":"prod","provider":"openai","model":"%s",
			 "clientMode":"ADVISORY","estimatedInputTokens":1000,"maxOutputTokens":500,"estimatedCostUsd":"0.000660",
			 "pricingVersion":"sdk-prices","metadata":{"feature":"support-summary"}%s}
			""".formatted(requestId, MODEL, extraFields);
	}

	private HttpResponse<String> post(String path, String apiKey, String idempotencyKey, String json) throws Exception {
		return send("POST", path, apiKey, idempotencyKey, json, "application/json");
	}

	private HttpResponse<String> send(String method, String path, String apiKey, String idempotencyKey, String json,
		String contentType) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", contentType)
			.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		if (apiKey != null) {
			builder.header("X-API-Key", apiKey);
		}
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
}
