package com.tokenledgercloud.api.domain.decision;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * control-plane-v1.yaml의 DecisionResponse. 처음 판정할 때 이 값을 JSON으로 저장하고, 같은 키·같은 본문의 재전송에는
 * 저장한 값을 그대로 돌려준다. 그래서 매핑 코드가 나중에 바뀌어도 이미 낸 판정의 응답은 바뀌지 않는다.
 * 금액과 시각은 계약대로 문자열이다(USD 소수 6자리, UTC ISO-8601).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponse(
	String schemaVersion,
	String decisionId,
	String requestId,
	DecisionOutcome outcome,
	EnforcementMode effectiveMode,
	List<Reason> reasons,
	String policyVersion,
	List<PolicyRef> policies,
	String requiredCostUsd,
	String remainingMonthlyBudgetUsd,
	String retryAt,
	String evaluatedAt,
	Reservation reservation
) {
	public record Reason(String code, String message) {
	}

	public record PolicyRef(String scope, String policyId, String policyVersion, boolean active) {
	}

	/** ENFORCE + ALLOW 판정이 잡아 둔 예산 예약. 클라이언트는 이 id로 정산한다. */
	public record Reservation(String reservationId, ReservationState state, String reservedCostUsd, String expiresAt) {
	}
}
