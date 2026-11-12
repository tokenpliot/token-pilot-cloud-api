package com.tokenledgercloud.api.domain.reservation;

import java.math.BigDecimal;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationSettlement;

/**
 * 정산 결과. control-plane-v1.yaml의 SettlementResult와 같은 필드다.
 *
 * @param requestId        요청 본문의 requestId를 그대로 돌려준다. (yaml: 클라이언트는 다른 값이 오면 INDETERMINATE로 버린다)
 * @param previousState    이 정산 직전의 상태. 재전송(duplicate)이면 처음 정산할 때의 값을 그대로 돌려준다.
 * @param committedCostUsd 이 정산으로 비용이 된 금액. COMMIT은 totalCostUsd, WRITE_OFF는 예약 금액. 그 밖에는 null.
 * @param releasedCostUsd  이 정산으로 예산에 돌려준 금액. RELEASE는 예약 금액 전부, COMMIT은 예약 - 실제(음수면 0). 그 밖에는 null.
 */
public record SettlementResult(
		String reservationId,
		String requestId,
		ReservationState previousState,
		ReservationState state,
		BigDecimal committedCostUsd,
		BigDecimal releasedCostUsd,
		boolean duplicate) {

	public static SettlementResult of(BudgetReservationSettlement settlement, String requestId, boolean duplicate) {
		return new SettlementResult(settlement.getReservationId(), requestId,
				settlement.getPreviousState(), settlement.getResultingState(),
				settlement.getCommittedCostUsd(), settlement.getReleasedCostUsd(), duplicate);
	}
}
