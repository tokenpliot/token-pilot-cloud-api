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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;

/**
 * 실제 HTTP 요청으로 Observe 수집 v2와 조회 API의 바깥쪽 동작을 확인한다.
 * 서비스 단위 테스트가 못 보는 것들: 필터(본문 크기), 보안 설정(401), 요청 검증(400), 응답 상태(201/200).
 * 서버를 임의 포트로 띄우고, API 키 검증만 가짜로 대체한다.
 */
// 서버를 임의 포트로 띄우고, 수집 v2를 켠 상태로 시험한다. 본문 한도는 작게 줄인다.
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ingestion-http;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.ingestion.max-event-bytes=2048",
		"token-pilot.observe.ingestion.enabled=true",
		"token-pilot.observe.ingestion.rate-unit-tokens=1000000"
	})
class ObserveIngestionHttpTest {

	@Value("${local.server.port}")
	private int port;

	// API 키 검증은 가짜로 대체한다.
	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	// 사용 이벤트 요청 본문을 만든다. 이벤트 id와 요청 id만 바꿔서 쓴다.
	private static String eventJson(String eventId) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod",
			  "eventId": "%s", "requestId": "req-%s", "schemaVersion": "observe-v1",
			  "tokenSemantics": "INCLUSIVE_V1", "provider": "openai", "model": "http-model",
			  "callStatus": "SUCCEEDED", "promptTokens": 100, "completionTokens": 50,
			  "cachedPromptTokens": 0, "reasoningTokens": 0, "occurredAt": "2026-10-10T12:00:00Z" }
			""".formatted(eventId, eventId);
	}

	// 지정한 경로로 JSON을 POST한다. API 키 헤더를 함께 보낸다.
	private HttpResponse<String> post(String rawPath, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("X-API-Key", "test-key")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@BeforeEach
	void setUp() {
		// support-copilot 프로젝트의 prod 환경 요청은 인증된 것으로 만든다.
		given(projectApiKeyAuthenticator.authenticate(any(), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(projectApiKeyAuthenticator);
	}

	@Test
	void newEventReturns201AndTheSameEventAgainReturns200AsDuplicate() throws Exception {
		HttpResponse<String> first = post("/api/v2/observe/events", eventJson("http-dup"));
		HttpResponse<String> second = post("/api/v2/observe/events", eventJson("http-dup"));

		// 처음은 새로 만들었으니 201, 같은 이벤트를 다시 보내면 200이고 중복으로 표시된다.
		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(first.body()).contains("\"duplicate\":false");
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(second.body()).contains("\"duplicate\":true");
	}

	@Test
	void invalidBodyIsRejectedWith400() throws Exception {
		// 필수 필드가 없는 본문은 요청 검증에서 400으로 거절된다.
		assertThat(post("/api/v2/observe/events", "{}").statusCode()).isEqualTo(400);
	}

	@Test
	void oversizedBodyIsRejectedWith413EvenWithEncodedPath() throws Exception {
		// 한도(2048바이트)를 넘는 본문은 인코딩한 경로로 보내도 413이다. (크기 제한 필터가 v2에도 적용됨)
		String oversized = eventJson("http-big").replaceFirst("\\}\\s*$", ", \"note\": \"" + "x".repeat(3000) + "\" }");

		assertThat(post("/api/v2/observe/events", oversized).statusCode()).isEqualTo(413);
		assertThat(post("/api/v2/observe/%65vents", oversized).statusCode()).isEqualTo(413);
	}

	@Test
	void ledgerQueryWithoutLoginReturns401NotARedirect() throws Exception {
		// 로그인 없이 조회 API를 부르면 로그인 페이지로 넘기지 않고 401이어야 한다. (HttpClient는 리다이렉트를 따라가지 않는다)
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/api/observe/ledger/unpriced?projectId=any"))
			.timeout(Duration.ofSeconds(20))
			.GET()
			.build();

		assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
	}

	@Test
	void malformedDateIsRejectedWith400NotAnInternalError() throws Exception {
		// 날짜 형식이 틀린 본문은 서버 오류(500)가 아니라 잘못된 입력(400)이어야 한다.
		HttpResponse<String> response = post("/api/v2/observe/events",
			eventJson("http-bad-date").replace("2026-10-10T12:00:00Z", "SECRET-NOT-A-DATE"));

		assertThat(response.statusCode()).isEqualTo(400);
		// 클라이언트가 보낸 값이 응답에 그대로 돌아오면 안 된다.
		assertThat(response.body()).doesNotContain("SECRET-NOT-A-DATE");
	}

	@Test
	void brokenJsonIsRejectedWith400() throws Exception {
		// JSON 문법이 깨진 본문도 400이어야 한다.
		assertThat(post("/api/v2/observe/events", "{ \"projectKey\": ").statusCode()).isEqualTo(400);
	}
}
