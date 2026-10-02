package com.tokenledgercloud.api.domain.decision;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 고객이 소유하는 집행 설정. 판정을 provider 호출 동작으로 바꾸는 유일한 근거다.
 *
 * @param mode             집행 모드
 * @param enforcedOutcomes ENFORCE 모드에서 집행할 판정 범위 ({@code DENY_POLICY}, {@code DENY_BUDGET}, {@code REQUIRE_APPROVAL} 중)
 * @param onIndeterminate  ENFORCE 모드에서 판정 불가 시 동작
 * @param onUnavailable    ENFORCE 모드에서 Control Plane 장애 시 동작
 */
public record EnforcementSettings(
	EnforcementMode mode,
	Set<DecisionOutcome> enforcedOutcomes,
	FailureBehavior onIndeterminate,
	FailureBehavior onUnavailable
) {

	private static final Set<DecisionOutcome> ENFORCEABLE = EnumSet.of(
		DecisionOutcome.DENY_POLICY,
		DecisionOutcome.DENY_BUDGET,
		DecisionOutcome.REQUIRE_APPROVAL
	);

	private static final Set<DecisionOutcome> DEFAULT_ENFORCED = EnumSet.of(
		DecisionOutcome.DENY_POLICY,
		DecisionOutcome.DENY_BUDGET
	);

	public EnforcementSettings {
		Objects.requireNonNull(mode, "mode must not be null");
		Objects.requireNonNull(enforcedOutcomes, "enforcedOutcomes must not be null");
		Objects.requireNonNull(onIndeterminate, "onIndeterminate must not be null");
		Objects.requireNonNull(onUnavailable, "onUnavailable must not be null");
		if (!ENFORCEABLE.containsAll(enforcedOutcomes)) {
			throw new IllegalArgumentException("enforcedOutcomes may only contain " + ENFORCEABLE);
		}
		enforcedOutcomes = Set.copyOf(enforcedOutcomes);
	}

	/**
	 * 새 연결의 기본값: OBSERVE, 기본 집행 범위, FAIL_OPEN.
	 */
	public static EnforcementSettings defaults() {
		return new EnforcementSettings(
			EnforcementMode.OBSERVE,
			DEFAULT_ENFORCED,
			FailureBehavior.FAIL_OPEN,
			FailureBehavior.FAIL_OPEN
		);
	}

	/**
	 * ADR 0001의 집행 행렬. OBSERVE는 언제나 진행, ADVISORY는 비차단 경고, ENFORCE만 고객 설정에 따라 막는다.
	 */
	public EnforcementAction actionFor(DecisionOutcome outcome) {
		Objects.requireNonNull(outcome, "outcome must not be null");
		if (mode == EnforcementMode.OBSERVE || outcome == DecisionOutcome.ALLOW) {
			return EnforcementAction.PROCEED;
		}
		if (mode == EnforcementMode.ADVISORY) {
			return EnforcementAction.PROCEED_WITH_ADVISORY;
		}
		return switch (outcome) {
			case DENY_POLICY, DENY_BUDGET -> enforcedOutcomes.contains(outcome)
				? EnforcementAction.BLOCK
				: EnforcementAction.PROCEED_WITH_ADVISORY;
			case REQUIRE_APPROVAL -> enforcedOutcomes.contains(outcome)
				? EnforcementAction.HOLD_FOR_APPROVAL
				: EnforcementAction.PROCEED_WITH_ADVISORY;
			case INDETERMINATE -> onFailure(onIndeterminate);
			case CONTROL_PLANE_UNAVAILABLE -> onFailure(onUnavailable);
			case ALLOW -> EnforcementAction.PROCEED;
		};
	}

	private static EnforcementAction onFailure(FailureBehavior behavior) {
		return behavior == FailureBehavior.FAIL_CLOSED
			? EnforcementAction.BLOCK
			: EnforcementAction.PROCEED_WITH_ADVISORY;
	}
}
