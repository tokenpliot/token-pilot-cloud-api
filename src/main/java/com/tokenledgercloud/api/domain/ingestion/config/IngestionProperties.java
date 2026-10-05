package com.tokenledgercloud.api.domain.ingestion.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ingestion limits and privacy switches. Defaults live in code so they hold in every environment;
 * override with {@code token-pilot.ingestion.*} properties, no code change needed.
 *
 * @param maxEventBytes         largest accepted body of {@code POST /api/ingestion/events}
 * @param maxBatchBytes         largest accepted body of {@code POST /api/ingestion/events/batch}
 * @param forbiddenMetadataKeys metadata keys (any depth, case-insensitive) that suggest raw prompt or response text; dropped before storage, or rejected in strict mode
 * @param strictMetadata        when true, forbidden keys and metadata outside the allowed format are rejected instead of dropped / only logged
 */
@ConfigurationProperties(prefix = "token-pilot.ingestion")
public record IngestionProperties(
	@DefaultValue("16384") int maxEventBytes,
	@DefaultValue("1048576") int maxBatchBytes,
	@DefaultValue({"prompt", "system_prompt", "completion", "messages", "content", "response"})
	List<String> forbiddenMetadataKeys,
	@DefaultValue("false") boolean strictMetadata
) {

	/** Same values as the {@code @DefaultValue}s, for code that runs without Spring (e.g. plain Validator use). */
	public static IngestionProperties defaults() {
		return new IngestionProperties(
			16_384,
			1_048_576,
			List.of("prompt", "system_prompt", "completion", "messages", "content", "response"),
			false
		);
	}
}
