package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 실제 Tomcat 위에서만 확인할 수 있는 수집 API의 바깥쪽 동작.
 * - 읽을 수 없는 요청 본문(날짜 형식 오류, 깨진 JSON)은 500이 아니라 400(COMMON-400)이고, 보낸 값이 응답·로그에 남지 않는다.
 * - 퍼센트 인코딩한 경로로 보내도 본문 크기 제한(413)을 피할 수 없다. (MockMvc는 경로 디코딩을 거치지 않아 여기서 확인한다)
 * 본문 해석과 크기 제한은 프로젝트 키 검증보다 먼저 일어나므로 키 검증을 가짜로 바꿀 필요가 없다.
 * 201/200/413 기본 동작과 꺼진 상태의 404는 ObserveIngestionHttpIntegrationTest, ObserveIngestionDisabledTest가 맡는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ingestion-http;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.ingestion.max-event-bytes=2048",
		"token-pilot.observe.ingestion.enabled=true",
		// 핸들러의 debug 로그까지 출력되게 해서, 로그에 입력값이 없다는 것을 실제 출력으로 확인한다.
		"logging.level.com.tokenledgercloud.api.global.exception=DEBUG"
	})
class ObserveIngestionHttpTest {

	private static final String SECRET = "SECRET-NOT-A-DATE";

	@Value("${local.server.port}")
	private int port;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	// Observe v2 사용 이벤트 요청 본문.
	private static String eventJson(String eventId) {
		return """
			{ "projectKey": "support-copilot", "environment": "prod",
			  "eventId": "%s", "requestId": "req-%s", "schemaVersion": "observe-v1",
			  "tokenSemantics": "INCLUSIVE_V1", "provider": "openai", "model": "http-model",
			  "callStatus": "SUCCEEDED", "promptTokens": 100, "completionTokens": 50,
			  "cachedPromptTokens": 0, "reasoningTokens": 0, "occurredAt": "2026-10-10T12:00:00Z" }
			""".formatted(eventId, eventId);
	}

	// 지정한 경로(인코딩된 그대로)로 JSON을 POST한다.
	private HttpResponse<String> post(String rawPath, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("X-API-Key", "test-key")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void malformedDateIsRejectedWith400AndTheValueIsNeitherEchoedNorLogged(CapturedOutput output) throws Exception {
		HttpResponse<String> response = post("/api/v2/observe/events",
			eventJson("http-bad-date").replace("2026-10-10T12:00:00Z", SECRET));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"COMMON-400\"").doesNotContain(SECRET);
		// 핸들러 로그는 실제로 찍혔고(예외 종류만), 보낸 값은 어디에도 없다.
		assertThat(output).contains("Unreadable request body: HttpMessageNotReadableException");
		assertThat(output).doesNotContain(SECRET);
	}

	@Test
	void brokenJsonIsRejectedWith400() throws Exception {
		HttpResponse<String> response = post("/api/v2/observe/events", "{ \"projectKey\": ");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"COMMON-400\"");
	}

	@Test
	void v1EndpointAlsoReturns400ForBrokenJson() throws Exception {
		// 전역 핸들러라 v1 단건 수집도 깨진 JSON에 500 대신 400을 돌려준다. (docs/api/ingestion-compat.md 참고)
		HttpResponse<String> response = post("/api/ingestion/events", "{ \"projectKey\": ");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"COMMON-400\"");
	}

	@Test
	void oversizedBodyIsRejectedWith413EvenWithEncodedPath() throws Exception {
		// %65 = 'e'. 인코딩한 철자로 보내도 같은 컨트롤러에 도달하므로 같은 한도(2048바이트)가 적용되어야 한다.
		String oversized = eventJson("http-big").replaceFirst("\\}\\s*$", ", \"note\": \"" + "x".repeat(3000) + "\" }");

		assertThat(post("/api/v2/observe/%65vents", oversized).statusCode()).isEqualTo(413);
	}
}
