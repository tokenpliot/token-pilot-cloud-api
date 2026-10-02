package com.tokenledgercloud.api.domain.decision;

/**
 * Control Plane 판정 결과. 의미와 HTTP 매핑은 ADR 0001을 따른다.
 */
public enum DecisionOutcome {
	ALLOW,
	DENY_POLICY,
	DENY_BUDGET,
	REQUIRE_APPROVAL,
	INDETERMINATE,
	CONTROL_PLANE_UNAVAILABLE;

	public boolean isPolicyDenial() {
		return this == DENY_POLICY || this == DENY_BUDGET;
	}

	/**
	 * 서버가 이 결과를 전달하는 HTTP 상태. 정책 판정은 정상 판정이므로 모두 200이다.
	 */
	public int httpStatus() {
		return this == CONTROL_PLANE_UNAVAILABLE ? 503 : 200;
	}

	/**
	 * 판정 응답을 받지 못한 클라이언트가 HTTP 오류 상태를 판정 결과로 바꾸는 규칙.
	 * 어떤 오류도 정책 거부로 바뀌지 않는다.
	 */
	public static DecisionOutcome forErrorStatus(int status) {
		if (status < 400 || status > 599) {
			throw new IllegalArgumentException("Not an HTTP error status: " + status);
		}
		if (status == 408 || status == 429 || status >= 500) {
			return CONTROL_PLANE_UNAVAILABLE;
		}
		return INDETERMINATE;
	}

	/**
	 * 연결 실패·타임아웃처럼 HTTP 응답 자체가 없는 경우의 판정 결과.
	 */
	public static DecisionOutcome forTransportFailure() {
		return CONTROL_PLANE_UNAVAILABLE;
	}
}
