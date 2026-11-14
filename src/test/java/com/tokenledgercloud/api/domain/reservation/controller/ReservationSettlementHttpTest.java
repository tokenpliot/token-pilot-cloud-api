package com.tokenledgercloud.api.domain.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.service.ReservationService;

/**
 * POST /api/control/v1/reservations/{id}/settlements 를 실제 HTTP로 확인한다: 보안 체인, 회원 키 필터 우회, 크기 필터,
 * 프로젝트 키 인증, 계약 형식의 오류 응답.
 */
// DecisionApiHttpTest와 같은 설정이라 같은 캐시 컨텍스트를 쓴다. (설정이 하나라도 다르면 컨텍스트가 늘어 전체 테스트가 메모리 부족)
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
class ReservationSettlementHttpTest {

	private static final String OCCURRED = "2026-11-14T09:00:06Z";

	@Value("${local.server.port}") int port;
	@Autowired ReservationService reservations;
	@Autowired BudgetReservationCounterRepository counters;
	@Autowired ProjectRepository projects;
	@Autowired ProjectApiKeyRepository apiKeys;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JdbcTemplate jdbc;

	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10)).build();

	ProjectKeyFixtures fixtures;
	Project project;
	String key;

	@BeforeEach
	void setUp() {
		clean();
		fixtures = new ProjectKeyFixtures(projects, apiKeys, passwordEncoder);
		project = fixtures.project("alpha", ProjectStatus.ACTIVE);
		key = fixtures.key(project, null, "ACTIVE", null).raw();
	}

	@AfterEach
	void clean() {
		for (String table : List.of("budget_reservation_settlements", "budget_reservations", "budget_reservation_counters",
			"project_api_keys", "project_environments", "projects")) {
			jdbc.update("delete from " + table);
		}
	}

	// 예약 하나를 만든다. 예상 비용 0.0175, prod 환경.
	private String reserve(Project owner, String requestId) {
		return reservations.reserve(new ReservationCommand(ProjectKeyFixtures.ORG, owner.getId(), "prod", "2026-11",
			"dec:" + requestId, requestId, "openai", "gpt-test", 1000L, 500L, new BigDecimal("0.0175"), null,
			new BigDecimal("10"), null, LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).reservation().getId();
	}

	private static String commit(String requestId, String occurredAt) {
		return """
			{"schemaVersion":"2026-10-01","requestId":"%s","result":"COMMIT",
			 "usage":{"promptTokens":2950,"completionTokens":720,"cachedPromptTokens":0,"reasoningTokens":0},
			 "cost":{"promptCostUsd":"0.007375","completionCostUsd":"0.007200","totalCostUsd":"0.014575"},
			 "occurredAt":"%s"}
			""".formatted(requestId, occurredAt);
	}

	private static String release(String requestId) {
		return """
			{"schemaVersion":"2026-10-01","requestId":"%s","result":"RELEASE","occurredAt":"%s"}
			""".formatted(requestId, OCCURRED);
	}

	private HttpResponse<String> post(String reservationId, String apiKey, String idempotencyKey, String json)
		throws Exception {
		return postPath("/api/control/v1/reservations/" + reservationId + "/settlements", apiKey, idempotencyKey, json);
	}

	private HttpResponse<String> postPath(String path, String apiKey, String idempotencyKey, String json)
		throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json));
		if (apiKey != null) {
			builder.header("X-API-Key", apiKey);
		}
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void commitSettlesTheReservationAndARetryReturnsTheSameResult() throws Exception {
		String id = reserve(project, "req-1");
		String stl = "stl:" + id + ":COMMIT";

		HttpResponse<String> first = post(id, key, stl, commit("req-1", OCCURRED));
		HttpResponse<String> retry = post(id, key, stl, commit("req-1", OCCURRED));

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.body()).contains("\"schemaVersion\":\"2026-10-01\"", "\"requestId\":\"req-1\"",
			"\"previousState\":\"RESERVED\"", "\"state\":\"COMMITTED\"", "\"committedCostUsd\":\"0.014575\"",
			"\"releasedCostUsd\":\"0.002925\"", "\"duplicate\":false");
		assertThat(retry.statusCode()).isEqualTo(200);
		assertThat(retry.body()).contains("\"duplicate\":true", "\"state\":\"COMMITTED\"");
		// 재시도가 와도 비용은 한 번만 반영된다.
		var counter = counters.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(project.getId(), "prod", "2026-11")
			.orElseThrow();
		assertThat(counter.getHeldUsd()).isEqualByComparingTo("0");
		assertThat(counter.getCommittedUsd()).isEqualByComparingTo("0.014575");
	}

	@Test
	void conflictsAre409() throws Exception {
		String id = reserve(project, "req-1");
		String stl = "stl:" + id + ":COMMIT";
		post(id, key, stl, commit("req-1", OCCURRED));

		// 같은 키에 occurredAt만 다른 본문
		HttpResponse<String> changed = post(id, key, stl, commit("req-1", "2026-11-14T09:00:07Z"));
		assertThat(changed.statusCode()).isEqualTo(409);
		assertThat(changed.body()).contains("\"code\":\"IDEMPOTENCY_CONFLICT\"", "\"outcome\":\"INDETERMINATE\"");
		// 이미 COMMITTED인 예약에 다른 결과
		HttpResponse<String> released = post(id, key, "stl:" + id + ":RELEASE", release("req-1"));
		assertThat(released.statusCode()).isEqualTo(409);
	}

	@Test
	void authenticationAndOwnershipAreCheckedBeforeTheReservationIsTouched() throws Exception {
		String id = reserve(project, "req-1");
		String stl = "stl:" + id + ":RELEASE";

		// 키 없음, 틀린 키
		assertThat(post(id, null, stl, release("req-1")).statusCode()).isEqualTo(401);
		assertThat(post(id, "tp_wrong_key_value", stl, release("req-1")).statusCode()).isEqualTo(401);
		// 다른 프로젝트의 키로는 예약이 보이지 않는다. (존재를 알려주지 않으므로 404)
		Project other = fixtures.project("beta", ProjectStatus.ACTIVE);
		String otherKey = fixtures.key(other, null, "ACTIVE", null).raw();
		assertThat(post(id, otherKey, stl, release("req-1")).statusCode()).isEqualTo(404);
		assertThat(post("no-such-reservation", key, stl, release("req-1")).statusCode()).isEqualTo(404);
		// 같은 프로젝트라도 dev 전용 키는 prod 예약을 정산할 수 없다.
		String devKey = fixtures.key(project, "dev", "ACTIVE", null).raw();
		assertThat(post(id, devKey, stl, release("req-1")).statusCode()).isEqualTo(403);
		// 그동안 예약은 그대로다.
		var counter = counters.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(project.getId(), "prod", "2026-11")
			.orElseThrow();
		assertThat(counter.getHeldUsd()).isEqualByComparingTo("0.0175");
	}

	@Test
	void contractViolationsAre400WithoutEchoingValues() throws Exception {
		String id = reserve(project, "req-1");
		String stl = "stl:" + id + ":COMMIT";

		// 정의되지 않은 필드(원문 prompt 등)는 거부하고 값은 돌려주지 않는다.
		HttpResponse<String> unknown = post(id, key, stl,
			commit("req-1", OCCURRED).replace("\"result\"", "\"prompt\":\"SECRET-PROMPT\",\"result\""));
		assertThat(unknown.statusCode()).isEqualTo(400);
		assertThat(unknown.body()).contains("\"code\":\"INVALID_REQUEST\"").doesNotContain("SECRET-PROMPT");
		// COMMIT인데 cost 없음
		assertThat(post(id, key, stl, commit("req-1", OCCURRED).replaceAll(",\\s*\"cost\":\\{[^}]*}", ""))
			.statusCode()).isEqualTo(400);
		// RELEASE에 cost
		assertThat(post(id, key, "stl:" + id + ":RELEASE", release("req-1")
			.replace("\"result\"", "\"cost\":{\"promptCostUsd\":\"0\",\"completionCostUsd\":\"0\",\"totalCostUsd\":\"0\"},\"result\""))
			.statusCode()).isEqualTo(400);
		// 클라이언트는 WRITE_OFF를 보낼 수 없다. (알 수 없는 enum 값)
		assertThat(post(id, key, stl, release("req-1").replace("RELEASE", "WRITE_OFF")).statusCode()).isEqualTo(400);
		// 짧은 Idempotency-Key, 서버 전용 접두사
		assertThat(post(id, key, "short", release("req-1")).statusCode()).isEqualTo(400);
		assertThat(post(id, key, "exp:" + id, release("req-1")).statusCode()).isEqualTo(400);
		assertThat(post(id, key, "ops:pretend-operator", release("req-1")).statusCode()).isEqualTo(400);
		// 예약을 만든 요청과 다른 requestId
		assertThat(post(id, key, "stl:" + id + ":RELEASE", release("req-other")).statusCode()).isEqualTo(400);
	}

	@Test
	void encodedPathReachesTheSameEndpointWithTheProjectKey() throws Exception {
		// %73 = 's'. 회원 키 필터와 크기 필터가 정규화한 경로로 판단하므로, 인코딩한 철자도 프로젝트 키로 인증된다.
		String id = reserve(project, "req-1");

		HttpResponse<String> response = postPath("/api/control/v1/reservations/" + id + "/%73ettlements", key,
			"stl:" + id + ":RELEASE", release("req-1"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"state\":\"RELEASED\"", "\"releasedCostUsd\":\"0.017500\"");
	}
}
