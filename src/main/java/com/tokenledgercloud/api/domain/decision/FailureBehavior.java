package com.tokenledgercloud.api.domain.decision;

/**
 * 판정 불가·Control Plane 장애 시 동작. ENFORCE 모드에서만 의미가 있으며 기본값은 {@link #FAIL_OPEN}이다.
 */
public enum FailureBehavior {
	FAIL_OPEN,
	FAIL_CLOSED
}
