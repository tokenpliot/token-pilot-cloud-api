package com.tokenledgercloud.api.domain.reservation;

import java.math.BigDecimal;
import java.time.Instant;

import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * 예약 하나에 대한 정산 요청. 필드는 control-plane-v1.yaml의 SettlementRequest와 같다.
 *
 * @param projectId      요청한 프로젝트 키의 프로젝트. 다른 프로젝트의 예약은 없는 것으로 취급한다(404).
 * @param idempotencyKey 정산 요청의 Idempotency-Key. SDK는 {@code stl:<reservationId>:<result>}를 쓴다. 서버는 해석하지 않는다.
 * @param requestId      예약을 만든 요청과 같은 값이어야 한다.
 * @param usage          COMMIT일 때 필수. 다른 종류에서는 비워야 한다.
 * @param cost           COMMIT일 때 필수. 정산 금액의 기준은 {@code totalCostUsd}이고, prompt·completion 분할은 검증하지 않는다.
 * @param occurredAt     정산 사건 시각. 재전송 비교(본문 비교)에 들어간다.
 */
public record SettlementCommand(
		String reservationId,
		String projectId,
		String idempotencyKey,
		String requestId,
		SettlementAction action,
		Usage usage,
		Cost cost,
		Instant occurredAt) {

	/** yaml UsageTokens. */
	public record Usage(long promptTokens, long completionTokens, Long cachedPromptTokens, Long reasoningTokens) {

		public Usage {
			ReservationCommand.requireNonNegative(promptTokens, "promptTokens");
			ReservationCommand.requireNonNegative(completionTokens, "completionTokens");
			ReservationCommand.requireNonNegative(cachedPromptTokens, "cachedPromptTokens");
			ReservationCommand.requireNonNegative(reasoningTokens, "reasoningTokens");
		}
	}

	/** yaml UsageCost. 금액은 소수점 6자리로 맞춘다. */
	public record Cost(BigDecimal promptCostUsd, BigDecimal completionCostUsd, BigDecimal cachedPromptCostUsd,
			BigDecimal reasoningCostUsd, BigDecimal totalCostUsd) {

		public Cost {
			promptCostUsd = nonNegative(promptCostUsd, "promptCostUsd");
			completionCostUsd = nonNegative(completionCostUsd, "completionCostUsd");
			cachedPromptCostUsd = cachedPromptCostUsd == null ? null : nonNegative(cachedPromptCostUsd, "cachedPromptCostUsd");
			reasoningCostUsd = reasoningCostUsd == null ? null : nonNegative(reasoningCostUsd, "reasoningCostUsd");
			totalCostUsd = nonNegative(totalCostUsd, "totalCostUsd");
		}

		/** 비용 분할 없이 총액만 아는 경우(테스트, 내부 호출)를 위한 도우미. */
		public static Cost total(BigDecimal totalCostUsd) {
			return new Cost(BigDecimal.ZERO, BigDecimal.ZERO, null, null, totalCostUsd);
		}

		private static BigDecimal nonNegative(BigDecimal value, String name) {
			BigDecimal usd = ReservationCommand.usd(value, name);
			if (usd.signum() < 0) {
				throw new IllegalArgumentException(name + " must not be negative");
			}
			return usd;
		}
	}

	public SettlementCommand {
		if (reservationId == null || reservationId.isBlank()) {
			throw new IllegalArgumentException("reservationId is required");
		}
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalArgumentException("projectId is required");
		}
		ReservationCommand.requireIdempotencyKey(idempotencyKey);
		ReservationCommand.requireIdentifier(requestId, "requestId");
		if (action == null) {
			throw new IllegalArgumentException("action is required");
		}
		if (action == SettlementAction.COMMIT) {
			// yaml: result=COMMIT이면 usage, cost 필수
			if (usage == null || cost == null) {
				throw new IllegalArgumentException("COMMIT requires usage and cost");
			}
		} else if (cost != null) {
			// 해제·미확인에 비용이 붙어 오면 어느 쪽이 맞는지 알 수 없으므로 받지 않는다.
			throw new IllegalArgumentException("cost is only allowed for COMMIT");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("occurredAt is required");
		}
	}

	/** COMMIT의 정산 금액. 분할 합계가 아니라 totalCostUsd를 쓴다. (control-plane-v1.yaml) */
	public BigDecimal totalCostUsd() {
		return cost == null ? null : cost.totalCostUsd();
	}

	/**
	 * 같은 정산의 재전송인지 판별하는 본문 지문(SHA-256 hex). 결과가 아니라 본문 전체를 비교한다.
	 * occurredAt까지 같아야 같은 정산이다. 금액은 소수점 6자리 문자열이라 "0.1"과 "0.100000"은 같다.
	 */
	public String fingerprint() {
		return HashingUtil.sha256("settlement-v2"
				+ "\n" + reservationId
				+ "\n" + requestId
				+ "\n" + action
				+ "\n" + (usage == null ? "null" : usage.promptTokens() + "," + usage.completionTokens()
						+ "," + usage.cachedPromptTokens() + "," + usage.reasoningTokens())
				+ "\n" + (cost == null ? "null" : plain(cost.promptCostUsd()) + "," + plain(cost.completionCostUsd())
						+ "," + plain(cost.cachedPromptCostUsd()) + "," + plain(cost.reasoningCostUsd())
						+ "," + plain(cost.totalCostUsd()))
				+ "\n" + occurredAt);
	}

	private static String plain(BigDecimal value) {
		return value == null ? "null" : value.toPlainString();
	}
}
