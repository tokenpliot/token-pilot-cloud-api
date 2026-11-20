package com.tokenledgercloud.api.domain.reservation;

import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;

/**
 * 예약 결과. 한도가 모자라면 예외가 아니라 granted=false로 돌려준다.
 * (ADR 0001 §3: 예산 거부는 정상 판정 DENY_BUDGET이지 오류가 아니다)
 *
 * @param reservation 예약된 행. granted=false이면 null.
 * @param duplicate   같은 Idempotency-Key·같은 요청이 다시 와서 기존 예약을 돌려준 경우 true.
 */
public record ReservationResult(boolean granted, BudgetReservation reservation, boolean duplicate) {

	public static ReservationResult created(BudgetReservation reservation) {
		return new ReservationResult(true, reservation, false);
	}

	public static ReservationResult existing(BudgetReservation reservation) {
		return new ReservationResult(true, reservation, true);
	}

	public static ReservationResult insufficientBudget() {
		return new ReservationResult(false, null, false);
	}
}
