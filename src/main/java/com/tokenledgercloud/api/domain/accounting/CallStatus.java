package com.tokenledgercloud.api.domain.accounting;

/**
 * usage_events.call_status 컬럼과 1:1로 대응하는 LLM 호출 결과 상태.
 */
public enum CallStatus {
    // 호출이 성공해서 실제 사용량이 보고된 경우.
    SUCCEEDED,
    // 공급자(OpenAI 등) 쪽 실패로 과금되지 않은 경우.
    PROVIDER_FAILED,
    // 호출은 됐을 수 있으나 실제 사용량을 알 수 없는 경우. 정산 확인이 필요하다.
    USAGE_UNKNOWN,
    // 클라이언트 정책(예산 등)이 호출 전에 막은 경우. 비용이 발생하지 않는다.
    BLOCKED_BY_CLIENT,
    // V6 이전 행. 호출 결과를 알 수 없다.
    LEGACY_UNKNOWN
}
