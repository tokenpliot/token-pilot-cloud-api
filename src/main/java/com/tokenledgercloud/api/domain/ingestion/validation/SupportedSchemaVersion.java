package com.tokenledgercloud.api.domain.ingestion.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** An absent {@code schemaVersion} is accepted (legacy SDKs); a present one must be a supported version. */
@Documented
@Constraint(validatedBy = SupportedSchemaVersionValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface SupportedSchemaVersion {

	String message() default "unsupported schemaVersion; supported versions: " + SupportedSchemaVersionValidator.SUPPORTED_LIST;

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
