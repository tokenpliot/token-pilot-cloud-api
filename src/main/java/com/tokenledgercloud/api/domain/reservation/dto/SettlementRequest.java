package com.tokenledgercloud.api.domain.reservation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tokenledgercloud.api.domain.decision.DecisionRequest.FieldViolation;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;

/**
 * POST /api/control/v1/reservations/{reservationId}/settlements 본문 (control-plane-v1.yaml의 SettlementRequest).
 *
 * <p>판정 요청과 같이 정의되지 않은 필드는 거부한다(additionalProperties: false). 모르는 필드는 이름만 모으고 값은 버린다.
 * 거부된 값은 오류 응답에 싣지 않는다.
 */
public class SettlementRequest {

	public static final String SCHEMA_VERSION = "2026-10-01";

	private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
	private static final Pattern USD_AMOUNT = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$");
	private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

	/** yaml의 result. 클라이언트는 WRITE_OFF를 보낼 수 없다(운영자 전용). */
	public enum Result {
		COMMIT, RELEASE, RECONCILIATION_REQUIRED;

		SettlementAction action() {
			return SettlementAction.valueOf(name());
		}
	}

	public String schemaVersion;
	public String requestId;
	public Result result;
	public Usage usage;
	public Cost cost;
	public String occurredAt;

	@JsonIgnore
	private final Set<String> unknownFields = new LinkedHashSet<>();

	@JsonAnySetter
	void unknownField(String name, Object ignoredValue) {
		unknownFields.add(name);
	}

	/** yaml UsageTokens. */
	public static class Usage {
		public Long promptTokens;
		public Long completionTokens;
		public Long cachedPromptTokens;
		public Long reasoningTokens;

		@JsonIgnore
		private final Set<String> unknownFields = new LinkedHashSet<>();

		@JsonAnySetter
		void unknownField(String name, Object ignoredValue) {
			unknownFields.add(name);
		}
	}

	/** yaml UsageCost. 금액은 부동소수점 오차를 피하려고 문자열이다. */
	public static class Cost {
		public String promptCostUsd;
		public String completionCostUsd;
		public String cachedPromptCostUsd;
		public String reasoningCostUsd;
		public String totalCostUsd;

		@JsonIgnore
		private final Set<String> unknownFields = new LinkedHashSet<>();

		@JsonAnySetter
		void unknownField(String name, Object ignoredValue) {
			unknownFields.add(name);
		}
	}

	/** 계약 위반을 필드별로 모은다. */
	public List<FieldViolation> violations() {
		List<FieldViolation> violations = new ArrayList<>();
		unknown("", unknownFields, violations);
		if (!SCHEMA_VERSION.equals(schemaVersion)) {
			violations.add(new FieldViolation("schemaVersion", "must be " + SCHEMA_VERSION));
		}
		if (requestId == null) {
			violations.add(new FieldViolation("requestId", "is required"));
		} else if (!IDENTIFIER.matcher(requestId).matches()) {
			violations.add(new FieldViolation("requestId", "must be 1 to 128 characters of letters, digits, '.', '_', ':' or '-'"));
		}
		if (result == null) {
			violations.add(new FieldViolation("result", "is required"));
		}
		if (usage != null) {
			unknown("usage.", usage.unknownFields, violations);
			tokens("usage.promptTokens", usage.promptTokens, true, violations);
			tokens("usage.completionTokens", usage.completionTokens, true, violations);
			tokens("usage.cachedPromptTokens", usage.cachedPromptTokens, false, violations);
			tokens("usage.reasoningTokens", usage.reasoningTokens, false, violations);
		}
		if (cost != null) {
			unknown("cost.", cost.unknownFields, violations);
			amount("cost.promptCostUsd", cost.promptCostUsd, true, violations);
			amount("cost.completionCostUsd", cost.completionCostUsd, true, violations);
			amount("cost.cachedPromptCostUsd", cost.cachedPromptCostUsd, false, violations);
			amount("cost.reasoningCostUsd", cost.reasoningCostUsd, false, violations);
			amount("cost.totalCostUsd", cost.totalCostUsd, true, violations);
		}
		if (result == Result.COMMIT) {
			// yaml: result=COMMIT이면 usage, cost 필수
			if (usage == null) {
				violations.add(new FieldViolation("usage", "is required for COMMIT"));
			}
			if (cost == null) {
				violations.add(new FieldViolation("cost", "is required for COMMIT"));
			}
		} else if (result != null && cost != null) {
			// 해제·미확인에 비용이 붙어 오면 어느 쪽이 맞는지 알 수 없어서 받지 않는다.
			violations.add(new FieldViolation("cost", "is only allowed for COMMIT"));
		}
		if (occurredAt == null) {
			violations.add(new FieldViolation("occurredAt", "is required"));
		} else if (parseInstant(occurredAt) == null) {
			violations.add(new FieldViolation("occurredAt", "must be an RFC 3339 date-time with an offset"));
		}
		return violations;
	}

	/** {@link #violations()}가 비어 있을 때만 호출한다. 프로젝트와 키는 인증 결과와 헤더에서 온다. */
	public SettlementCommand toCommand(String reservationId, String projectId, String idempotencyKey) {
		SettlementCommand.Usage commandUsage = usage == null ? null : new SettlementCommand.Usage(
				usage.promptTokens, usage.completionTokens, usage.cachedPromptTokens, usage.reasoningTokens);
		SettlementCommand.Cost commandCost = cost == null ? null : new SettlementCommand.Cost(
				new BigDecimal(cost.promptCostUsd), new BigDecimal(cost.completionCostUsd),
				cost.cachedPromptCostUsd == null ? null : new BigDecimal(cost.cachedPromptCostUsd),
				cost.reasoningCostUsd == null ? null : new BigDecimal(cost.reasoningCostUsd),
				new BigDecimal(cost.totalCostUsd));
		return new SettlementCommand(reservationId, projectId, idempotencyKey, requestId, result.action(),
				commandUsage, commandCost, parseInstant(occurredAt));
	}

	private static Instant parseInstant(String value) {
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private static void unknown(String prefix, Set<String> names, List<FieldViolation> violations) {
		for (String name : names) {
			String shown = FIELD_NAME.matcher(name).matches() ? prefix + name : prefix + "(invalid name)";
			violations.add(new FieldViolation(shown, "not allowed"));
		}
	}

	private static void tokens(String field, Long value, boolean required, List<FieldViolation> violations) {
		if (value == null) {
			if (required) {
				violations.add(new FieldViolation(field, "is required"));
			}
		} else if (value < 0) {
			violations.add(new FieldViolation(field, "must not be negative"));
		}
	}

	private static void amount(String field, String value, boolean required, List<FieldViolation> violations) {
		if (value == null) {
			if (required) {
				violations.add(new FieldViolation(field, "is required"));
			}
		} else if (!USD_AMOUNT.matcher(value).matches()) {
			violations.add(new FieldViolation(field, "must be a USD amount string with up to 6 decimals"));
		}
	}
}
