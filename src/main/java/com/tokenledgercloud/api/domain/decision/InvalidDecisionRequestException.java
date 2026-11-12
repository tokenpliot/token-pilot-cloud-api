package com.tokenledgercloud.api.domain.decision;

import java.util.List;

/** 요청이 판정 계약을 어겼다. 거부된 값은 담지 않고 필드 이름과 이유만 담는다. */
public class InvalidDecisionRequestException extends RuntimeException {

	private final transient List<DecisionRequest.FieldViolation> violations;

	public InvalidDecisionRequestException(List<DecisionRequest.FieldViolation> violations) {
		super("Request does not match the decision contract.");
		this.violations = List.copyOf(violations);
	}

	public List<DecisionRequest.FieldViolation> violations() {
		return violations;
	}
}
