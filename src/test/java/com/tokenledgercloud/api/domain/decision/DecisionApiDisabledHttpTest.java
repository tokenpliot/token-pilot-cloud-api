package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 기능 플래그(token-pilot.decision.enabled)를 켜지 않으면 판정 API는 없다. SDK는 이 404를 INDETERMINATE로 다루므로,
 * 응답이 정상적인 "경로 없음"이어야 하고 판정 결과를 담아서는 안 된다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:decision-disabled;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
	})
class DecisionApiDisabledHttpTest {

	@Value("${local.server.port}") int port;
	@Autowired ApplicationContext context;

	@Test
	void defaultIsOffSoTheControllerIsNotRegistered() {
		assertThat(context.getBeanNamesForType(DecisionController.class)).isEmpty();
	}

	@Test
	void disabledApiAnswers404WithNullDataAndNoOutcome() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/control/v1/decisions"))
				.header("Content-Type", "application/json").header("X-API-Key", "tpk_live_anything")
				.header("Idempotency-Key", "dec:req-1")
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(404);
		JsonNode json = ControlPlaneSpec.assertValid("PlainErrorEnvelope", response.body());
		assertThat(json.get("data").isNull()).isTrue();
		assertThat(response.body()).doesNotContain("outcome");
	}
}
