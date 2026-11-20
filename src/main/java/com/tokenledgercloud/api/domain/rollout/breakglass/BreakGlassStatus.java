package com.tokenledgercloud.api.domain.rollout.breakglass;

/** break-glass grant의 현재 상태. 저장하지 않고 조회 시각 기준으로 계산한다. */
public enum BreakGlassStatus {
	// 발급됐고 아직 만료·취소되지 않았다. 이 범위의 Enforce를 우회할 수 있다.
	ACTIVE,
	// 만료 시각이 지났다.
	EXPIRED,
	// 운영자가 만료 전에 취소했다.
	REVOKED
}
