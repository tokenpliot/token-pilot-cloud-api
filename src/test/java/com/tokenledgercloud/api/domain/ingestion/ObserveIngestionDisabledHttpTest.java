package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 수집 v2를 켜지 않은 기본 설정에서는 엔드포인트가 아예 없어야 한다. 기본 꺼짐이 실제로 지켜지는지 확인한다.
 */
// 서버를 임의 포트로 띄우되, token-pilot.observe.ingestion.enabled는 설정하지 않는다.
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ingestion-disabled;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
	})
class ObserveIngestionDisabledHttpTest {

	@Value("${local.server.port}")
	private int port;

	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@Test
	void observeIngestionEndpointDoesNotExistByDefault() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/observe/events"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build();

		// 컨트롤러가 등록되지 않았으므로 404여야 한다. (400이나 401이면 엔드포인트가 켜져 있다는 뜻이다)
		assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
	}
}
