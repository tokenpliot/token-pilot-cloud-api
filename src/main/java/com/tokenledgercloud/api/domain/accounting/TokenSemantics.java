package com.tokenledgercloud.api.domain.accounting;

/**
 * usage_events.token_semantics 컬럼과 1:1로 대응하는 토큰 의미. V6의 CHECK 제약 값과 같다.
 * 토큰 항목을 합산할 때 어떤 항목이 서로 포함되어 있는지를 나타낸다.
 */
public enum TokenSemantics {
	// V6 이전 행. 포함/배타 의미를 알 수 없다. 새 수집에는 쓰지 않는다.
	LEGACY_UNKNOWN,
	// 신규 계약 v1: 전체 토큰(total)에 나머지 항목이 포함되어 있다는 의미.
	INCLUSIVE_V1
}
