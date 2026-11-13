package com.tokenledgercloud.api.domain.reservation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationSettlement;

/**
 * 예약에 적용된 정산 한 건(이력). source로 누가 보낸 정산인지 구분한다.
 *
 * @param source CLIENT(SDK 정산), EXPIRY(서버 만료 회수), OPERATOR(운영자 수동 정산)
 */
public record SettlementView(
		SettlementAction action,
		ReservationState previousState,
		ReservationState resultingState,
		BigDecimal committedCostUsd,
		BigDecimal releasedCostUsd,
		LocalDateTime occurredAt,
		LocalDateTime recordedAt,
		String source) {

	// 정산 키 접두사로 출처를 나눈다. (exp:는 서버 만료 회수, ops:는 운영 API, 그 밖은 클라이언트)
	public static SettlementView from(BudgetReservationSettlement settlement) {
		String key = settlement.getIdempotencyKey();
		String source = key.startsWith("exp:") ? "EXPIRY" : key.startsWith("ops:") ? "OPERATOR" : "CLIENT";
		return new SettlementView(settlement.getAction(), settlement.getPreviousState(),
				settlement.getResultingState(), settlement.getCommittedCostUsd(), settlement.getReleasedCostUsd(),
				settlement.getOccurredAt(), settlement.getCreatedAt(), source);
	}
}
