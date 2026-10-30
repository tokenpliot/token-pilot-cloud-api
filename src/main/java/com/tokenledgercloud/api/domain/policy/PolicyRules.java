package com.tokenledgercloud.api.domain.policy;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** Complete replacement, never a partial patch. Null request limits mean no limit at this scope. */
public record PolicyRules(
	String currency,
	@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlySoftLimitUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyHardLimitUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requestSoftLimitUsd,
	@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requestHardLimitUsd,
	Boolean active,
	List<Integer> notificationThresholds
) {
	public PolicyRules {
		if (!"USD".equals(currency) || monthlyHardLimitUsd == null || active == null) {
			throw invalid("USD, monthlyHardLimitUsd and active are required.");
		}
		monthlySoftLimitUsd = amount(monthlySoftLimitUsd);
		monthlyHardLimitUsd = amount(monthlyHardLimitUsd);
		requestSoftLimitUsd = amount(requestSoftLimitUsd);
		requestHardLimitUsd = amount(requestHardLimitUsd);
		ordered(monthlySoftLimitUsd, monthlyHardLimitUsd);
		ordered(requestSoftLimitUsd, requestHardLimitUsd);
		if (notificationThresholds == null || notificationThresholds.size() > 3
			|| notificationThresholds.stream().anyMatch(v -> v == null || !List.of(50, 80, 100).contains(v))
			|| notificationThresholds.stream().distinct().count() != notificationThresholds.size()) {
			throw invalid("notificationThresholds must be a distinct subset of [50, 80, 100].");
		}
		notificationThresholds = notificationThresholds.stream().sorted().toList();
	}

	private static BigDecimal amount(BigDecimal value) {
		if (value == null) return null;
		if (value.signum() < 0 || value.stripTrailingZeros().scale() > 6
			|| value.compareTo(new BigDecimal("999999999999.999999")) > 0) {
			throw invalid("Limits must be nonnegative USD amounts with at most 12 integer and 6 fractional digits.");
		}
		return value.setScale(6);
	}

	private static void ordered(BigDecimal soft, BigDecimal hard) {
		if (soft != null && hard != null && soft.compareTo(hard) > 0) {
			throw invalid("A soft limit must not exceed its hard limit.");
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(ErrorCode.INVALID_INPUT, message);
	}
}
