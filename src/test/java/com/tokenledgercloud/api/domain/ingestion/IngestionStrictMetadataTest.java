package com.tokenledgercloud.api.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/** The strict-metadata switch, flipped by configuration only, on the application's real validator. */
@SpringBootTest(properties = "token-pilot.ingestion.strict-metadata=true")
class IngestionStrictMetadataTest {

	@Autowired
	private Validator validator;

	private static IngestionEventRequest event(Map<String, Object> metadata) {
		return new IngestionEventRequest(
			"support-copilot", "prod", "req-1", "openai", "gpt-4o-mini",
			10L, 5L, null, null, null,
			BigDecimal.ONE, BigDecimal.ONE, null, null, null,
			null, "2026-05-01", null, metadata,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null, null
		);
	}

	@Test
	void formatViolationsAreRejectedWhenStrict() {
		assertThat(validator.validate(event(Map.of("count", 5)))).singleElement().satisfies(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo("metadata");
			assertThat(violation.getMessage()).contains("NON_STRING_VALUE");
		});
		assertThat(validator.validate(event(Map.of("Bad-Key", "v")))).hasSize(1);
		assertThat(validator.validate(event(Map.of("note", "x".repeat(257))))).hasSize(1);
	}

	@Test
	void wellFormedMetadataStillPasses() {
		assertThat(validator.validate(event(Map.of("feature", "summary")))).isEmpty();
	}

	@Test
	void forbiddenKeysStillRejected() {
		assertThat(validator.validate(event(Map.of("prompt", "SECRET-VALUE")))).extracting(ConstraintViolation::getMessage)
			.singleElement().asString().isEqualTo("metadata contains a forbidden key")
			.doesNotContain("prompt", "SECRET-VALUE");
		assertThat(validator.validate(event(Map.of("ctx", Map.of("Messages", java.util.List.of("x"))))))
			.hasSize(1);
	}
}
