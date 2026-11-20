package com.tokenledgercloud.api.domain.reservation;

import com.tokenledgercloud.api.domain.decision.ReservationState;

/**
 * 예약에 대한 정산 요청의 종류. 앞의 세 개는 control-plane-v1.yaml SettlementRequest.result와 같다.
 * WRITE_OFF는 클라이언트가 보내지 않고, 운영자가 수동 정산(후속 작업)에서만 쓴다.
 */
public enum SettlementAction {
	// 실제 사용량으로 정산한다. 비용이 필요하다.
	COMMIT(ReservationState.COMMITTED),
	// 호출하지 않았거나 실패해서 예약을 푼다.
	RELEASE(ReservationState.RELEASED),
	// 사용량을 알 수 없다. 예약 금액을 부채로 계속 잡아둔다.
	RECONCILIATION_REQUIRED(ReservationState.RECONCILIATION_REQUIRED),
	// 결과를 끝내 확인하지 못한 예약을 닫는다. 예약 금액을 비용으로 확정한다. (0원으로 만들지 않는다)
	WRITE_OFF(ReservationState.WRITTEN_OFF);

	private final ReservationState target;

	SettlementAction(ReservationState target) {
		this.target = target;
	}

	// 이 정산이 끝나면 예약이 갈 상태.
	public ReservationState target() {
		return target;
	}
}
