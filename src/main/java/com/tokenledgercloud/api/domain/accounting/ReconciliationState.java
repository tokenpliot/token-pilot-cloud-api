package com.tokenledgercloud.api.domain.accounting;

/**
 * 이슈 #11의 "미정산 상태 조회"에 쓰는 파생 상태.
 * DB 컬럼이 아니라 다른 컬럼 값으로 계산한다. (컬럼을 더 늘리지 않기 위한 결정)
 */
public enum ReconciliationState {
    // 정산 관점에서 확인할 것이 없는 상태.
    NONE,
    // 실제 사용량은 있으나 가격을 찾지 못해 금액이 비어 있는 상태. 0달러가 아니다.
    UNPRICED,
    // 격리되었거나 사용량을 알 수 없어 사람이 대조해야 하는 상태.
    RECONCILIATION_REQUIRED
}
