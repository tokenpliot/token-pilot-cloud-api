package com.tokenledgercloud.api.domain.decision;

/**
 * 클라이언트가 판정을 받은 뒤 provider 호출에 대해 취하는 동작.
 */
public enum EnforcementAction {
	PROCEED,
	PROCEED_WITH_ADVISORY,
	BLOCK,
	HOLD_FOR_APPROVAL;

	public boolean stopsProviderCall() {
		return this == BLOCK || this == HOLD_FOR_APPROVAL;
	}
}
