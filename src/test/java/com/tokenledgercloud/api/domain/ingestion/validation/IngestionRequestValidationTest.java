package com.tokenledgercloud.api.domain.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class IngestionRequestValidationTest {

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private Logger logger;

	@BeforeEach
	void captureLogs() {
		logger = (Logger) LoggerFactory.getLogger(IngestionMetadataConstraintValidator.class);
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		logger.detachAppender(logs);
	}

	private static Validator validator(IngestionProperties properties) {
		ConstraintValidatorFactory factory = new ConstraintValidatorFactory() {
			@Override
			public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
				try {
					if (key == IngestionMetadataConstraintValidator.class) {
						return key.cast(new IngestionMetadataConstraintValidator(properties));
					}
					return key.getDeclaredConstructor().newInstance();
				} catch (ReflectiveOperationException exception) {
					throw new IllegalStateException(exception);
				}
			}

			@Override
			public void releaseInstance(ConstraintValidator<?, ?> instance) {
			}
		};
		return Validation.byDefaultProvider().configure().constraintValidatorFactory(factory)
			.buildValidatorFactory().getValidator();
	}

	private static IngestionProperties props(boolean strict, String... forbidden) {
		return new IngestionProperties(16_384, 1_048_576, List.of(forbidden), strict);
	}

	private static IngestionEventRequest event(Map<String, Object> metadata, String schemaVersion) {
		return new IngestionEventRequest(
			"support-copilot", "prod", "req-1", "openai", "gpt-4o-mini",
			10L, 5L, null, null, null,
			BigDecimal.ONE, BigDecimal.ONE, null, null, null,
			null, "2026-05-01", null, metadata,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null, schemaVersion
		);
	}

	private static IngestionEventItemRequest item(Map<String, Object> metadata) {
		return new IngestionEventItemRequest(
			"req-1", "openai", "gpt-4o-mini",
			10L, 5L, null, null, null,
			BigDecimal.ONE, BigDecimal.ONE, null, null, null,
			null, "2026-05-01", null, metadata,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null
		);
	}

	private static Map<String, Object> sloppyMetadata() {
		Map<String, Object> metadata = new HashMap<>();
		for (int i = 0; i < 17; i++) {
			metadata.put("k" + i, "v");
		}
		metadata.put("Tenant-Id", "SECRET-TENANT-VALUE");
		metadata.put("count", 5);
		metadata.put("long", "L".repeat(300));
		return metadata;
	}

	// --- schemaVersion ---

	@Test
	void absentOrBlankSchemaVersionIsAccepted() {
		Validator validator = validator(IngestionProperties.defaults());

		assertThat(validator.validate(event(null, null))).isEmpty();
		assertThat(validator.validate(event(null, " "))).isEmpty();
	}

	@Test
	void supportedSchemaVersionIsAccepted() {
		assertThat(validator(IngestionProperties.defaults()).validate(event(null, "2026-10-01"))).isEmpty();
	}

	@Test
	void unknownSchemaVersionIsRejectedWithoutEchoingTheValue() {
		Set<ConstraintViolation<IngestionEventRequest>> violations =
			validator(IngestionProperties.defaults()).validate(event(null, "2099-01-01"));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo("schemaVersion");
			assertThat(violation.getMessage()).contains("2026-10-01").doesNotContain("2099-01-01");
		});
	}

	@Test
	void batchSchemaVersionIsChecked() {
		Validator validator = validator(IngestionProperties.defaults());

		assertThat(validator.validate(new IngestionBatchRequest("p", "prod", List.of(item(null)), null))).isEmpty();
		assertThat(validator.validate(new IngestionBatchRequest("p", "prod", List.of(item(null)), "2026-10-01")))
			.isEmpty();
		assertThat(validator.validate(new IngestionBatchRequest("p", "prod", List.of(item(null)), "nope")))
			.singleElement().satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("schemaVersion"));
	}

	// --- forbidden keys ---

	@Test
	void strictModeRejectsAForbiddenKeyWithoutNamingItOrItsValue() {
		Set<ConstraintViolation<IngestionEventRequest>> violations = validator(props(true, "prompt"))
			.validate(event(Map.of("Prompt", "TOP-SECRET-PROMPT-TEXT"), null));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo("metadata");
			assertThat(violation.getMessage()).isEqualTo("metadata contains a forbidden key")
				.doesNotContainIgnoringCase("prompt").doesNotContain("TOP-SECRET-PROMPT-TEXT");
		});
	}

	@Test
	void strictModeRejectsAForbiddenKeyOnBatchItemsToo() {
		assertThat(validator(props(true, "messages")).validate(item(Map.of("messages", "x"))))
			.singleElement().satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("metadata"));
	}

	@Test
	void defaultModePassesAForbiddenKeyAndLogsOnlyKindAndCount() {
		assertThat(validator(props(false, "prompt")).validate(event(Map.of("Prompt", "TOP-SECRET-PROMPT-TEXT"), null)))
			.isEmpty();

		assertThat(logs.list).singleElement().satisfies(entry -> {
			assertThat(entry.getFormattedMessage()).contains("FORBIDDEN_KEY").contains("count=1")
				.doesNotContainIgnoringCase("prompt").doesNotContain("TOP-SECRET-PROMPT-TEXT");
		});
	}

	@Test
	void defaultModeStillJudgesTheFormatOfTheRemainingMetadata() {
		// only the forbidden key is removed; a loose-format rest is a warning, not a rejection
		assertThat(validator(props(false, "prompt")).validate(event(Map.of("prompt", "x", "Bad-Key", "v"), null)))
			.isEmpty();
		assertThat(logs.list).hasSize(2);
		assertThat(logs.list.get(1).getFormattedMessage()).contains("INVALID_KEY_FORMAT")
			.doesNotContain("Bad-Key");
	}

	@Test
	void forbiddenListFromConfigurationIsUsed() {
		Validator validator = validator(props(true, "secret_note"));

		assertThat(validator.validate(event(Map.of("secret_note", "x"), null))).hasSize(1);
		assertThat(validator.validate(event(Map.of("prompt", "x"), null))).isEmpty();
	}

	// --- format violations: warn by default, reject in strict mode ---

	@Test
	void formatViolationsPassWithAWarningThatHasNoKeysOrValues() {
		assertThat(validator(IngestionProperties.defaults()).validate(event(sloppyMetadata(), null))).isEmpty();

		assertThat(logs.list).singleElement().satisfies(entry -> {
			assertThat(entry.getLevel()).isEqualTo(Level.WARN);
			String message = entry.getFormattedMessage();
			assertThat(message).contains("TOO_MANY_ENTRIES", "INVALID_KEY_FORMAT", "NON_STRING_VALUE",
				"VALUE_TOO_LONG");
			assertThat(message).doesNotContain("Tenant-Id", "SECRET-TENANT-VALUE", "LLLL", "k1");
		});
	}

	@Test
	void wellFormedMetadataLogsNothing() {
		assertThat(validator(IngestionProperties.defaults())
			.validate(event(Map.of("feature", "summary"), null))).isEmpty();

		assertThat(logs.list).isEmpty();
	}

	@Test
	void strictModeRejectsFormatViolationsWithoutEchoingKeysOrValues() {
		Set<ConstraintViolation<IngestionEventRequest>> violations =
			validator(props(true, "prompt")).validate(event(sloppyMetadata(), null));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo("metadata");
			assertThat(violation.getMessage()).contains("TOO_MANY_ENTRIES", "NON_STRING_VALUE")
				.doesNotContain("Tenant-Id", "SECRET-TENANT-VALUE", "LLLL");
		});
		assertThat(logs.list).isEmpty();
	}

	@Test
	void strictModeAcceptsWellFormedMetadata() {
		assertThat(validator(props(true, "prompt")).validate(event(Map.of("feature", "summary"), null))).isEmpty();
	}

	@Test
	void absentMetadataIsAlwaysValid() {
		assertThat(validator(props(true, "prompt")).validate(event(null, null))).isEmpty();
		assertThat(validator(props(true, "prompt")).validate(event(Map.of(), null))).isEmpty();
	}
}
