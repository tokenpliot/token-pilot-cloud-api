package com.tokenledgercloud.api.domain.ingestion.validation;

import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SupportedSchemaVersionValidator implements ConstraintValidator<SupportedSchemaVersion, String> {

	/** Event schema versions this server understands. A new date-based version is added here. */
	static final String SUPPORTED_LIST = "2026-10-01";

	public static final Set<String> SUPPORTED = Set.of(SUPPORTED_LIST);

	@Override
	public boolean isValid(String schemaVersion, ConstraintValidatorContext context) {
		return schemaVersion == null || schemaVersion.isBlank() || SUPPORTED.contains(schemaVersion);
	}
}
