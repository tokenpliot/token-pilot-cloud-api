package com.tokenledgercloud.api.domain.ingestion.validation;

import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy.Evaluation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Forbidden raw-text keys are always rejected. Other format violations are logged and let through unless
 * {@code token-pilot.ingestion.strict-metadata} is on, so existing SDK calls keep working.
 *
 * <p>Spring injects the bound {@link IngestionProperties}; plain Bean Validation (no Spring) uses the no-arg
 * constructor with the built-in defaults. Logs carry violation kinds and counts only, never keys or values.
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
			context.disableDefaultConstraintViolation();
			// The key is one of the configured forbidden names, not free-form client input.
			context.unwrap(HibernateConstraintValidatorContext.class)
				.addMessageParameter("key", evaluation.forbiddenKey())
				.buildConstraintViolationWithTemplate("metadata must not contain raw text field '{key}'")
				.addConstraintViolation();
			return false;
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
