package com.tokenledgercloud.api.domain.decision;

/**
 * 고객이 고르는 집행 모드. 새 연결의 기본값은 {@link #OBSERVE}다.
 */
public enum EnforcementMode {
	/** 사용량과 판정을 기록만 한다. */
	OBSERVE,
	/** 판정을 경고로 전달하지만 호출을 막지 않는다. */
	ADVISORY,
	/** 고객이 고른 집행 범위의 판정만 차단·승인 대기로 집행한다. 명시적 opt-in. */
	ENFORCE
}
