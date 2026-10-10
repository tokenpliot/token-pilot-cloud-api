package com.tokenledgercloud.api.domain.accounting;

/**
 * usage_events.accounting_status 컬럼과 1:1로 대응하는 회계 검증 상태.
 * V6 마이그레이션의 CHECK 제약 값과 문자열이 정확히 같아야 한다.
 */
public enum AccountingStatus {
    // V6 이전에 저장된 기존 행. 회계 검증을 거치지 않았으므로 원장 집계에서 제외한다.
    LEGACY_UNVERIFIED,
    // 수신은 됐지만 아직 회계 값(가격/실제 사용량)이 확정되지 않은 상태.
    PENDING,
    // 회계 값 검증이 끝난 상태.
    VERIFIED,
    // 귀속(프로젝트/환경) 또는 값에 문제가 있어 격리된 상태. 사람이 확인해야 한다.
    QUARANTINED
}
