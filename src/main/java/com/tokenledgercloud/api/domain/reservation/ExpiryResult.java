package com.tokenledgercloud.api.domain.reservation;

/**
 * 만료 예약 회수 한 번의 결과.
 *
 * @param released               해제(RELEASED)한 예약 수. 예약 금액을 예산으로 돌려줬다.
 * @param reconciliationRequired 결과 미확인(RECONCILIATION_REQUIRED)으로 돌린 예약 수. 예약 금액을 부채로 계속 잡아둔다.
 * @param skipped                그 사이 다른 요청(클라이언트 정산, 다른 서버의 회수)이 먼저 처리해서 건너뛴 예약 수.
 */
public record ExpiryResult(int released, int reconciliationRequired, int skipped) {

	// 이번에 상태를 바꾼 예약 수.
	public int processed() {
		return released + reconciliationRequired;
	}
}
