package com.tokenledgercloud.api.domain.ingestion.validation;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy.Evaluation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Strict mode ({@code token-pilot.ingestion.strict-metadata}) rejects forbidden raw-text keys and any format
 * violation. The default mode lets the request through: forbidden keys are dropped later, before storage
 * ({@link IngestionMetadataPolicy#sanitize}), and format violations are only logged, so existing SDK calls keep
 * working.
 *
 * <p>Spring injects the bound {@link IngestionProperties}; plain Bean Validation (no Spring) uses the no-arg
 * constructor with the built-in defaults. Logs and error messages carry violation kinds and counts only, never keys or values.
 */
public class IngestionMetadataConstraintValidator implements ConstraintValidator<ValidIngestionMetadata, Map<String, Object>> {

	private static final Logger log = LoggerFactory.getLogger(IngestionMetadataConstraintValidator.class);

	private final IngestionMetadataPolicy policy;
	private final boolean strict;

	public IngestionMetadataConstraintValidator() {
		this(IngestionProperties.defaults());
	}

	@Autowired
	public IngestionMetadataConstraintValidator(IngestionProperties properties) {
		this.policy = new IngestionMetadataPolicy(properties);
		this.strict = properties.strictMetadata();
	}

	@Override
	public boolean isValid(Map<String, Object> metadata, ConstraintValidatorContext context) {
		if (metadata == null || metadata.isEmpty()) {
			return true;
		}

		Evaluation evaluation = policy.evaluate(metadata);

		if (evaluation.forbiddenKey() != null) {
			if (strict) {
				context.disableDefaultConstraintViolation();
				context.buildConstraintViolationWithTemplate("metadata contains a forbidden key")
					.addConstraintViolation();
				return false;
			}
			// Default mode: the key is dropped before storage; judge the rest of the format without it.
			int removed = policy.sanitize(metadata).removed();
			log.warn("Ingestion metadata contained forbidden keys that were dropped: kind=FORBIDDEN_KEY, count={}",
				removed);
			evaluation = policy.evaluate(policy.sanitize(metadata).metadata());
		}

		if (evaluation.violations().isEmpty()) {
			return true;
		}

		String kinds = evaluation.violations().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
		if (strict) {
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate("metadata must follow the allowed format (" + kinds + ")")
				.addConstraintViolation();
			return false;
		}

		log.warn("Ingestion metadata is outside the allowed format and was accepted: kinds={}, offendingEntries={}",
			kinds, evaluation.offending());
		return true;
	}
}
