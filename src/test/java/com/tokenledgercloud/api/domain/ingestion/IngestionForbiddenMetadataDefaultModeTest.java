package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Default (non-strict) mode: a forbidden metadata key is dropped, the event is stored, and the key name and its
 * value appear nowhere: not in the database, the logs or the response.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ForbiddenMetadataLeakTestSupport.FORBIDDEN_KEYS)
class IngestionForbiddenMetadataDefaultModeTest extends ForbiddenMetadataLeakTestSupport {

	private void assertNothingLeaked(HttpResponse<String> response, String requestId) {
		assertThat(response.body()).doesNotContainIgnoringCase("leaky").doesNotContain(SECRET_VALUE);
		assertThat(storedMetadata(requestId)).doesNotContainIgnoringCase("leaky").doesNotContain(SECRET_VALUE);
		assertThat(capturedLogs()).doesNotContain("leaky").doesNotContain(SECRET_VALUE.toLowerCase());
	}

	@Test
	void singleEventIsStoredWithoutTheForbiddenKeysAndNothingLeaks() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events", eventJson("req-1", leakyMetadata()));

		assertThat(response.statusCode()).isEqualTo(201);
		assertNothingLeaked(response, "req-1");
		assertThat(storedMetadata("req-1")).contains("feature", "summary", "team", "support", "role", "user");
		// a warning is logged with the kind and count only
		assertThat(capturedLogs()).contains("forbidden_key").contains("count=3");
	}

	@Test
	void batchItemIsStoredNotRejectedAndNothingLeaks() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events/batch", batchJson("req-b1", leakyMetadata()));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parse(response).path("data").path("createdCount").asInt()).isEqualTo(1);
		assertThat(parse(response).path("data").path("rejectedCount").asInt()).isZero();
		assertThat(parse(response).path("data").path("items").get(0).path("status").asText()).isEqualTo("CREATED");
		assertNothingLeaked(response, "req-b1");
	}

	@Test
	void metadataThatOnlyHadForbiddenKeysIsStoredAsEmpty() throws Exception {
		HttpResponse<String> response = post("/api/ingestion/events",
			eventJson("req-only", "{\"leaky_key\": \"" + SECRET_VALUE + "\"}"));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(storedMetadata("req-only")).isNull();
		assertThat(response.body()).doesNotContain(SECRET_VALUE);
	}

	@Test
	void fingerprintIgnoresForbiddenKeysSoTheSameEventWithOrWithoutThemIsADuplicateNotAConflict() throws Exception {
		HttpResponse<String> first = post("/api/ingestion/events", eventJson("req-fp", leakyMetadata()));
		HttpResponse<String> withOtherSecret = post("/api/ingestion/events", eventJson("req-fp",
			leakyMetadata().replace(SECRET_VALUE, "ANOTHER-SECRET-TEXT")));
		HttpResponse<String> withoutKeys = post("/api/ingestion/events", eventJson("req-fp",
			"{\"feature\": \"summary\", \"ctx\": {\"team\": \"support\"}, \"turns\": [{\"role\": \"user\"}]}"));
		HttpResponse<String> differentRealMetadata = post("/api/ingestion/events", eventJson("req-fp",
			"{\"feature\": \"other\", \"leaky_key\": \"x\"}"));

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(parse(first).path("data").path("duplicate").asBoolean()).isFalse();
		assertThat(withOtherSecret.statusCode()).isEqualTo(201);
		assertThat(parse(withOtherSecret).path("data").path("duplicate").asBoolean()).isTrue();
		assertThat(withoutKeys.statusCode()).isEqualTo(201);
		assertThat(parse(withoutKeys).path("data").path("duplicate").asBoolean()).isTrue();
		// a real difference in the remaining metadata is still a conflict
		assertThat(differentRealMetadata.statusCode()).isEqualTo(409);
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}
}
