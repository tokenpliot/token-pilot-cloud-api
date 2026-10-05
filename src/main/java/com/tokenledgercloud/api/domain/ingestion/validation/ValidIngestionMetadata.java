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

/** Ingestion metadata must not carry raw prompt/response keys and (in strict mode) must follow the allowed format. */
@Documented
@Constraint(validatedBy = IngestionMetadataConstraintValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface ValidIngestionMetadata {

	String message() default "metadata is not allowed";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
