package com.tokenledgercloud.api.domain.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy.Evaluation;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy.Violation;

class IngestionMetadataPolicyTest {

	private final IngestionMetadataPolicy policy = new IngestionMetadataPolicy(IngestionProperties.defaults());

	@Test
	void nullAndEmptyMetadataAreClean() {
		assertThat(policy.evaluate(null).violations()).isEmpty();
		assertThat(policy.evaluate(Map.of()).forbiddenKey()).isNull();
	}

	@Test
	void wellFormedMetadataHasNoFindings() {
		Evaluation evaluation = policy.evaluate(Map.of("feature", "support-summary", "team.name", "a_b-c"));

		assertThat(evaluation.forbiddenKey()).isNull();
		assertThat(evaluation.violations()).isEmpty();
	}

	@Test
	void forbiddenKeysAreFoundCaseInsensitivelyAtAnyDepth() {
		assertThat(policy.evaluate(Map.of("prompt", "x")).forbiddenKey()).isEqualTo("prompt");
		assertThat(policy.evaluate(Map.of("PROMPT", "x")).forbiddenKey()).isEqualTo("prompt");
		assertThat(policy.evaluate(Map.of("ctx", Map.of("Messages", List.of()))).forbiddenKey()).isEqualTo("messages");
		assertThat(policy.evaluate(Map.of("turns", List.of(Map.of("content", "hi")))).forbiddenKey())
			.isEqualTo("content");
		assertThat(policy.evaluate(Map.of("system_prompt", "x")).forbiddenKey()).isEqualTo("system_prompt");
	}

	@Test
	void keysThatOnlyContainAForbiddenWordAreNotForbidden() {
		assertThat(policy.evaluate(Map.of("prompt_tokens_hint", "x", "contents", "y", "response_code", "z"))
			.forbiddenKey()).isNull();
	}

	@Test
	void forbiddenValuesAreNotInspected() {
		// only keys count: a value that mentions the word is fine
		assertThat(policy.evaluate(Map.of("note", "this is not a prompt")).forbiddenKey()).isNull();
	}

	@Test
	void formatViolationsAreReportedByKindWithoutKeysOrValues() {
		Map<String, Object> metadata = new HashMap<>();
		for (int i = 0; i < 17; i++) {
			metadata.put("k" + i, "v");
		}
		metadata.put("Bad-Key", "v");
		metadata.put("number", 5);
		metadata.put("long", "x".repeat(257));

		Evaluation evaluation = policy.evaluate(metadata);

		assertThat(evaluation.forbiddenKey()).isNull();
		assertThat(evaluation.violations()).containsExactlyInAnyOrder(
			Violation.TOO_MANY_ENTRIES, Violation.INVALID_KEY_FORMAT, Violation.NON_STRING_VALUE,
			Violation.VALUE_TOO_LONG);
		assertThat(evaluation.offending()).isPositive();
		assertThat(evaluation.toString()).doesNotContain("Bad-Key").doesNotContain("xxxx");
	}

	@Test
	void boundaryValuesAreAllowed() {
		Map<String, Object> metadata = new HashMap<>();
		for (int i = 0; i < 16; i++) {
			metadata.put("k" + i, "v");
		}
		metadata.put("k0", "x".repeat(256));
		metadata.put("a".repeat(64), "v");
		metadata.remove("k15");

		assertThat(policy.evaluate(metadata).violations()).isEmpty();
	}

	@Test
	void nonStringValuesIncludingNestedObjectsAreFlagged() {
		assertThat(policy.evaluate(Map.of("n", 1)).violations()).containsExactly(Violation.NON_STRING_VALUE);
		assertThat(policy.evaluate(Map.of("o", Map.of("a", "b"))).violations())
			.containsExactly(Violation.NON_STRING_VALUE);
	}

	@Test
	void configuredForbiddenListReplacesTheDefaults() {
		IngestionMetadataPolicy custom =
			new IngestionMetadataPolicy(new IngestionProperties(1, 1, List.of("Secret_Note"), false));

		assertThat(custom.evaluate(Map.of("secret_note", "x")).forbiddenKey()).isEqualTo("Secret_Note");
		assertThat(custom.evaluate(Map.of("prompt", "x")).forbiddenKey()).isNull();
	}

	@Test
	void emptyForbiddenListForbidsNothing() {
		IngestionMetadataPolicy none = new IngestionMetadataPolicy(new IngestionProperties(1, 1, List.of(), false));

		assertThat(none.evaluate(Map.of("prompt", "x")).forbiddenKey()).isNull();
	}
}
