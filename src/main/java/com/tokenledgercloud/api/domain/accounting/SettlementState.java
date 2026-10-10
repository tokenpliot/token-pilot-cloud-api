package com.tokenledgercloud.api.domain.accounting;

/**
 * usage_events.settlement_state 컬럼과 1:1로 대응하는 정산 상태.
 */
public enum SettlementState {
    // 실제 금액까지 확정되어 정산이 끝난 상태.
    SETTLED,
    // 아직 정산이 끝나지 않은 상태. 기본값이다.
    UNSETTLED
}
