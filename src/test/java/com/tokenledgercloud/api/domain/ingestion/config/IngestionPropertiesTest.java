package com.tokenledgercloud.api.domain.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;

class IngestionPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(IngestionConfig.class)
		.withBean(LocalValidatorFactoryBean.class);

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
	void defaultsBindWithoutAnyConfiguration() {
		runner.run(context -> {
			IngestionProperties properties = context.getBean(IngestionProperties.class);

			assertThat(properties.maxEventBytes()).isEqualTo(16 * 1024);
			assertThat(properties.maxBatchBytes()).isEqualTo(1024 * 1024);
			assertThat(properties.strictMetadata()).isFalse();
			assertThat(properties).isEqualTo(IngestionProperties.defaults());
		});
	}

	@Test
	void everyValueCanBeOverriddenByConfiguration() {
		runner.withPropertyValues(
				"token-pilot.ingestion.max-event-bytes=100",
				"token-pilot.ingestion.max-batch-bytes=200",
				"token-pilot.ingestion.forbidden-metadata-keys=secret_note,raw_text",
				"token-pilot.ingestion.strict-metadata=true")
			.run(context -> {
				IngestionProperties properties = context.getBean(IngestionProperties.class);

				assertThat(properties.maxEventBytes()).isEqualTo(100);
				assertThat(properties.maxBatchBytes()).isEqualTo(200);
				assertThat(properties.forbiddenMetadataKeys()).containsExactly("secret_note", "raw_text");
				assertThat(properties.strictMetadata()).isTrue();
			});
	}

	@Test
	void springInjectsTheBoundPropertiesIntoTheMetadataValidator() {
		runner.withPropertyValues("token-pilot.ingestion.forbidden-metadata-keys=secret_note")
			.run(context -> {
				LocalValidatorFactoryBean validator = context.getBean(LocalValidatorFactoryBean.class);

				// the validator only rejects forbidden keys in strict mode; here it must still not reject "prompt"
				assertThat(validator.validate(event(Map.of("secret_note", "x")))).isEmpty();
				assertThat(validator.validate(event(Map.of("prompt", "x")))).isEmpty();
			});
		runner.withPropertyValues("token-pilot.ingestion.forbidden-metadata-keys=secret_note",
				"token-pilot.ingestion.strict-metadata=true")
			.run(context -> {
				LocalValidatorFactoryBean validator = context.getBean(LocalValidatorFactoryBean.class);

				assertThat(validator.validate(event(Map.of("secret_note", "x")))).hasSize(1);
				assertThat(validator.validate(event(Map.of("prompt", "x")))).isEmpty();
			});
	}

	@Test
	void springInjectedValidatorHonoursStrictMode() {
		runner.withPropertyValues("token-pilot.ingestion.strict-metadata=true")
			.run(context -> {
				LocalValidatorFactoryBean validator = context.getBean(LocalValidatorFactoryBean.class);

				assertThat(validator.validate(event(Map.of("count", 5)))).hasSize(1);
				assertThat(validator.validate(event(Map.of("feature", "ok")))).isEmpty();
			});
	}
}
