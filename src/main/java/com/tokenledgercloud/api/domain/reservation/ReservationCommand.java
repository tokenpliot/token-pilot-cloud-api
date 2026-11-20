package com.tokenledgercloud.api.domain.reservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * 예산 예약 요청 한 건. 판정 API(ENFORCE + ALLOW)가 클라이언트 요청과 서버가 정한 값(정책 context, 한도, 금액)을 묶어 만든다.
 *
 * @param budgetPeriod               월 예산 기간 "yyyy-MM". 같은 기간의 예약끼리 한도를 나눠 쓴다.
 * @param idempotencyKey             판정 요청의 Idempotency-Key. 고유 범위는 (프로젝트, 엔드포인트)다. 서버는 해석하지 않는다.
 * @param reservedCostUsd            예상 비용 = 잡아둘 금액(예상 부채). 0보다 커야 한다.
 * @param projectBudgetLimitUsd      프로젝트 전체 월 예산. 활성 정책이 없으면 null(제한 없음).
 * @param environmentBudgetLimitUsd  이 환경의 월 예산. 활성 정책이 없으면 null(제한 없음).
 *                                   (#14: 두 범위가 모두 있으면 둘 다 만족해야 한다)
 * @param policyRequestContextId     #14 PolicyContextService.capture가 만든 요청 context. 예약 시점의 정책·가격 스냅샷을 가리킨다.
 * @param expiresAt                  이 시각(UTC)이 지나도록 정산이 없으면 만료 회수 대상이다.
 */
public record ReservationCommand(
		String organizationId,
		String projectId,
		String environment,
		String budgetPeriod,
		String idempotencyKey,
		String requestId,
		String provider,
		String model,
		Long estimatedPromptTokens,
		Long estimatedCompletionTokens,
		BigDecimal reservedCostUsd,
		BigDecimal projectBudgetLimitUsd,
		BigDecimal environmentBudgetLimitUsd,
		String policyRequestContextId,
		LocalDateTime expiresAt) {

	// control-plane-v1.yaml의 Identifier 규칙과 같다.
	private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
	// 월 예산 기간.
	private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

	// 입력이 잘못되면 DB에 가기 전에 여기서 막는다. 금액은 소수점 6자리로 맞춘다. (decimal(18,6))
	public ReservationCommand {
		requireText(organizationId, "organizationId", 36);
		requireText(projectId, "projectId", 36);
		requireText(environment, "environment", 20);
		if (budgetPeriod == null || !PERIOD.matcher(budgetPeriod).matches()) {
			throw new IllegalArgumentException("budgetPeriod must be yyyy-MM");
		}
		requireIdempotencyKey(idempotencyKey);
		requireIdentifier(requestId, "requestId");
		requireText(provider, "provider", 128);
		requireText(model, "model", 128);
		requireNonNegative(estimatedPromptTokens, "estimatedPromptTokens");
		requireNonNegative(estimatedCompletionTokens, "estimatedCompletionTokens");
		reservedCostUsd = usd(reservedCostUsd, "reservedCostUsd");
		if (reservedCostUsd.signum() <= 0) {
			throw new IllegalArgumentException("reservedCostUsd must be positive");
		}
		projectBudgetLimitUsd = optionalLimit(projectBudgetLimitUsd, "projectBudgetLimitUsd");
		environmentBudgetLimitUsd = optionalLimit(environmentBudgetLimitUsd, "environmentBudgetLimitUsd");
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiresAt is required");
		}
	}

	/**
	 * 같은 Idempotency-Key로 다시 온 요청이 '같은 요청'인지 판별하는 지문(SHA-256 hex).
	 * 클라이언트가 보낸 값만 넣는다. 서버가 정한 값(정책 context, 금액, 한도, 기간)은 재시도 사이에 바뀔 수 있어서 넣지 않는다.
	 * 환경은 키의 고유 범위가 아니라 본문이므로 여기에 넣는다.
	 */
	public String fingerprint() {
		return HashingUtil.sha256("reservation-v2"
				+ "\n" + environment
				+ "\n" + requestId
				+ "\n" + provider
				+ "\n" + model
				+ "\n" + estimatedPromptTokens
				+ "\n" + estimatedCompletionTokens);
	}

	// 금액을 소수점 6자리로 맞춘다. 7자리 이상이면 반올림하지 않고 거절한다. (금액이 조용히 바뀌지 않게)
	static BigDecimal usd(BigDecimal value, String name) {
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		try {
			return value.setScale(6, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(name + " must have at most 6 decimal places");
		}
	}

	// 없으면 null(제한 없음), 있으면 0 이상의 6자리 금액.
	private static BigDecimal optionalLimit(BigDecimal value, String name) {
		if (value == null) {
			return null;
		}
		BigDecimal limit = usd(value, name);
		if (limit.signum() < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
		return limit;
	}

	// control-plane-v1.yaml의 Idempotency-Key 헤더: 8~128자 불투명 문자열.
	static void requireIdempotencyKey(String value) {
		if (value == null || value.length() < 8 || value.length() > 128 || value.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must be 8..128 characters");
		}
	}

	static void requireIdentifier(String value, String name) {
		if (value == null || !IDENTIFIER.matcher(value).matches()) {
			throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
		}
	}

	static void requireNonNegative(Long value, String name) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
	}

	private static void requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank() || value.length() > maxLength) {
			throw new IllegalArgumentException(name + " must be 1.." + maxLength + " characters");
		}
	}
}
