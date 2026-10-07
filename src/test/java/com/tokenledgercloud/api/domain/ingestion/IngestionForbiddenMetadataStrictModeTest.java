package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Strict mode: a forbidden metadata key is a 400 (a rejected item in a batch), nothing is stored, and neither the
 * key name nor its value appears in the response or the logs.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {ForbiddenMetadataLeakTestSupport.FORBIDDEN_KEYS, "token-pilot.ingestion.strict-metadata=true"})
class IngestionForbiddenMetadataStrictModeTest extends ForbiddenMetadataLeakTestSupport {

	@Test
	void singleEventIs400AndNothingLeaks() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events", eventJson("req-1", leakyMetadata()));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(parse(response).path("errors").get(0).path("reason").asText())
			.isEqualTo("metadata contains a forbidden key");
		assertThat(response.body()).doesNotContainIgnoringCase("leaky").doesNotContain(SECRET_VALUE);
		assertThat(capturedLogs()).doesNotContain("leaky").doesNotContain(SECRET_VALUE.toLowerCase());
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void batchItemIsRejectedAndNothingLeaks() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events/batch", batchJson("req-b1", leakyMetadata()));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parse(response).path("data").path("rejectedCount").asInt()).isEqualTo(1);
		assertThat(parse(response).path("data").path("items").get(0).path("status").asText()).isEqualTo("REJECTED");
		assertThat(response.body()).doesNotContainIgnoringCase("leaky").doesNotContain(SECRET_VALUE);
		assertThat(capturedLogs()).doesNotContain("leaky").doesNotContain(SECRET_VALUE.toLowerCase());
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void contractFormatRulesStillApply() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-fmt", "{\"Bad-Key\": \"v\"}"));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("INVALID_KEY_FORMAT").doesNotContain("Bad-Key");
	}
}
