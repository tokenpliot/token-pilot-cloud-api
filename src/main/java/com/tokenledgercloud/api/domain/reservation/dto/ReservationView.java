package com.tokenledgercloud.api.domain.reservation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;

/**
 * 운영 조회용 예약 한 건. 시각은 모두 UTC다. 멱등성 키와 요청 지문은 내부 값이라 내보내지 않는다.
 *
 * @param reservedCostUsd  예약 시점의 예상 부채
 * @param committedCostUsd 비용으로 확정된 금액 (종료 전에는 null)
 * @param releasedCostUsd  예산으로 돌려준 금액 (종료 전에는 null)
 */
public record ReservationView(
		String reservationId,
		String environment,
		String budgetPeriod,
		String requestId,
		String provider,
		String model,
		ReservationState state,
		BigDecimal reservedCostUsd,
		BigDecimal committedCostUsd,
		BigDecimal releasedCostUsd,
		LocalDateTime expiresAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	public static ReservationView from(BudgetReservation reservation) {
		return new ReservationView(reservation.getId(), reservation.getEnvironment(), reservation.getBudgetPeriod(),
				reservation.getRequestId(), reservation.getProvider(), reservation.getModel(), reservation.getState(),
				reservation.getReservedCostUsd(), reservation.getCommittedCostUsd(), reservation.getReleasedCostUsd(),
				reservation.getExpiresAt(), reservation.getCreatedAt(), reservation.getUpdatedAt());
	}
}
