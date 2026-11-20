package com.tokenledgercloud.api.domain.decision;

import java.util.EnumSet;
import java.util.Set;

/**
 * ENFORCE 예산 예약의 상태. 이름과 전이는 ADR 0001 §6과 control-plane-v1.yaml의 ReservationState를 따른다.
 *
 * <pre>
 * RESERVED ──▶ IN_FLIGHT ──▶ COMMITTED
 *    │             │
 *    │             ├──▶ RECONCILIATION_REQUIRED ──▶ COMMITTED | WRITTEN_OFF
 *    │             └──▶ RELEASED
 *    └──▶ RELEASED (호출 전 취소·만료)
 * </pre>
 */
public enum ReservationState {
	RESERVED,
	IN_FLIGHT,
	RECONCILIATION_REQUIRED,
	COMMITTED,
	RELEASED,
	WRITTEN_OFF;

	/**
	 * 더 이상 바뀌지 않는 상태인지. 종료 상태에 같은 정산이 다시 오면 원래 결과를 돌려준다.
	 */
	public boolean isTerminal() {
		return this == COMMITTED || this == RELEASED || this == WRITTEN_OFF;
	}

	/**
	 * 예약 금액을 아직 예산에서 잡아두고 있는(미정산 부채로 남아 있는) 상태인지.
	 * 결과를 모르는 RECONCILIATION_REQUIRED도 0원으로 풀지 않고 계속 잡아둔다.
	 */
	public boolean holdsBudget() {
		return this == RESERVED || this == IN_FLIGHT || this == RECONCILIATION_REQUIRED;
	}

	/**
	 * 이 상태에서 바로 갈 수 있는 다음 상태들.
	 */
	public Set<ReservationState> next() {
		return switch (this) {
			case RESERVED -> EnumSet.of(IN_FLIGHT, RELEASED);
			case IN_FLIGHT -> EnumSet.of(COMMITTED, RECONCILIATION_REQUIRED, RELEASED);
			case RECONCILIATION_REQUIRED -> EnumSet.of(COMMITTED, WRITTEN_OFF);
			case COMMITTED, RELEASED, WRITTEN_OFF -> EnumSet.noneOf(ReservationState.class);
		};
	}

	public boolean canTransitionTo(ReservationState target) {
		return next().contains(target);
	}
}
