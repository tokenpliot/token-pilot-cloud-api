package com.tokenledgercloud.api.domain.decision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * POST /api/control/v1/decisions 본문(control-plane-v1.yaml의 DecisionRequest).
 *
 * <p>정의되지 않은 필드는 거부한다. Spring Boot의 Jackson은 기본적으로 모르는 필드를 무시하므로, 모르는 필드의 이름만
 * {@link #unknownFields}에 모으고 값은 버린다. 원문 prompt 같은 값이 이 객체나 로그, 오류 응답에 남지 않는다.
 */
public class DecisionRequest {

	static final String SCHEMA_VERSION = "2026-10-01";

	private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
	private static final Pattern USD_AMOUNT = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$");
	private static final Pattern METADATA_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
	private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
	private static final int MAX_METADATA_ENTRIES = 16;
	private static final int MAX_METADATA_VALUE_LENGTH = 256;

	public String schemaVersion;
	public String requestId;
	public String environment;
	public String provider;
	public String model;
	public EnforcementMode clientMode;
	public Long estimatedInputTokens;
	public Long maxOutputTokens;
	public String estimatedCostUsd;
	public String pricingVersion;
	public Map<String, Object> metadata;

	@JsonIgnore
	private final Set<String> unknownFields = new LinkedHashSet<>();

	/** 모르는 필드는 이름만 기록하고 값은 읽자마자 버린다. */
	@JsonAnySetter
	void unknownField(String name, Object ignoredValue) {
		unknownFields.add(name);
	}

	/** 계약 위반을 필드별로 모은다. 거부된 값은 메시지에 싣지 않는다. */
	public List<FieldViolation> violations() {
		List<FieldViolation> violations = new ArrayList<>();
		for (String name : unknownFields) {
			String shown = FIELD_NAME.matcher(name).matches() ? name : "(invalid name)";
			violations.add(new FieldViolation(shown, "not allowed"));
		}
		if (!SCHEMA_VERSION.equals(schemaVersion)) {
			violations.add(new FieldViolation("schemaVersion", "must be " + SCHEMA_VERSION));
		}
		identifier("requestId", requestId, true, violations);
		identifier("environment", environment, true, violations);
		identifier("provider", provider, true, violations);
		identifier("model", model, true, violations);
		identifier("pricingVersion", pricingVersion, false, violations);
		if (clientMode == null) {
			violations.add(new FieldViolation("clientMode", "is required"));
		}
		tokens("estimatedInputTokens", estimatedInputTokens, violations);
		tokens("maxOutputTokens", maxOutputTokens, violations);
		if (estimatedCostUsd != null && !USD_AMOUNT.matcher(estimatedCostUsd).matches()) {
			violations.add(new FieldViolation("estimatedCostUsd", "must be a USD amount string with up to 6 decimals"));
		}
		metadata(violations);
		return violations;
	}

	/** {@link #violations()}가 비어 있을 때만 호출한다. */
	public DecisionCommand toCommand() {
		Map<String, String> strings = new LinkedHashMap<>();
		if (metadata != null) {
			metadata.forEach((key, value) -> strings.put(key, (String) value));
		}
		return new DecisionCommand(schemaVersion, requestId, environment, provider, model, clientMode,
			estimatedInputTokens, maxOutputTokens, estimatedCostUsd == null ? null : new BigDecimal(estimatedCostUsd),
			pricingVersion, strings);
	}

	private void metadata(List<FieldViolation> violations) {
		if (metadata == null) {
			return;
		}
		if (metadata.size() > MAX_METADATA_ENTRIES) {
			violations.add(new FieldViolation("metadata", "must have at most " + MAX_METADATA_ENTRIES + " entries"));
		}
		for (Map.Entry<String, Object> entry : metadata.entrySet()) {
			if (!METADATA_KEY.matcher(entry.getKey()).matches()) {
				violations.add(new FieldViolation("metadata", "has a key with an invalid format"));
			}
			if (!(entry.getValue() instanceof String value) || value.length() > MAX_METADATA_VALUE_LENGTH) {
				violations.add(new FieldViolation("metadata", "values must be strings of at most "
					+ MAX_METADATA_VALUE_LENGTH + " characters"));
			}
		}
	}

	private static void identifier(String field, String value, boolean required, List<FieldViolation> violations) {
		if (value == null) {
			if (required) {
				violations.add(new FieldViolation(field, "is required"));
			}
		} else if (!IDENTIFIER.matcher(value).matches()) {
			violations.add(new FieldViolation(field, "must be 1 to 128 characters of letters, digits, '.', '_', ':' or '-'"));
		}
	}

	private static void tokens(String field, Long value, List<FieldViolation> violations) {
		if (value == null) {
			violations.add(new FieldViolation(field, "is required"));
		} else if (value < 0) {
			violations.add(new FieldViolation(field, "must not be negative"));
		}
	}

	public record FieldViolation(String field, String message) {
	}
}
