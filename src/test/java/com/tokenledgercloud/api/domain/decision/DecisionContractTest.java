package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionContractTest {

	private static final Set<DecisionOutcome> DEFAULT_SCOPE =
		EnumSet.of(DecisionOutcome.DENY_POLICY, DecisionOutcome.DENY_BUDGET);

	private static EnforcementSettings enforce(
		Set<DecisionOutcome> scope,
		FailureBehavior onIndeterminate,
		FailureBehavior onUnavailable
	) {
		return new EnforcementSettings(EnforcementMode.ENFORCE, scope, onIndeterminate, onUnavailable);
	}

	@ParameterizedTest
	@EnumSource(DecisionOutcome.class)
	void defaultSettingsNeverBlockProviderCall(DecisionOutcome outcome) {
		EnforcementSettings defaults = EnforcementSettings.defaults();

		assertThat(defaults.mode()).isEqualTo(EnforcementMode.OBSERVE);
		assertThat(defaults.actionFor(outcome)).isEqualTo(EnforcementAction.PROCEED);
	}

	@ParameterizedTest
	@EnumSource(DecisionOutcome.class)
	void advisoryNeverBlocksEvenWithFailClosedAndFullScope(DecisionOutcome outcome) {
		EnforcementSettings advisory = new EnforcementSettings(
			EnforcementMode.ADVISORY,
			EnumSet.of(DecisionOutcome.DENY_POLICY, DecisionOutcome.DENY_BUDGET, DecisionOutcome.REQUIRE_APPROVAL),
			FailureBehavior.FAIL_CLOSED,
			FailureBehavior.FAIL_CLOSED
		);

		assertThat(advisory.actionFor(outcome).stopsProviderCall()).isFalse();
	}

	@Test
	void everyHttpErrorStatusMapsToFailureOutcomeNeverPolicyDenial() {
		for (int status = 400; status <= 599; status++) {
			DecisionOutcome outcome = DecisionOutcome.forErrorStatus(status);

			assertThat(outcome.isPolicyDenial()).as("status %d", status).isFalse();
			assertThat(outcome).as("status %d", status)
				.isIn(DecisionOutcome.INDETERMINATE, DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
		}
		assertThat(DecisionOutcome.forTransportFailure()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
	}

	@ParameterizedTest
	@ValueSource(ints = {408, 429, 500, 502, 503, 504})
	void overloadAndServerErrorsMeanControlPlaneUnavailable(int status) {
		assertThat(DecisionOutcome.forErrorStatus(status)).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 409, 422})
	void clientErrorsIncludingRevokedKeyMeanIndeterminate(int status) {
		assertThat(DecisionOutcome.forErrorStatus(status)).isEqualTo(DecisionOutcome.INDETERMINATE);
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 302, 399, 600})
	void nonErrorStatusIsRejected(int status) {
		assertThatThrownBy(() -> DecisionOutcome.forErrorStatus(status))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void onlyControlPlaneUnavailableTravelsAsServerError() {
		for (DecisionOutcome outcome : DecisionOutcome.values()) {
			int expected = outcome == DecisionOutcome.CONTROL_PLANE_UNAVAILABLE ? 503 : 200;
			assertThat(outcome.httpStatus()).as(outcome.name()).isEqualTo(expected);
		}
	}

	@Test
	void enforceWithDefaultFailureBehaviorBlocksPolicyDenialsButNotOutages() {
		EnforcementSettings settings = enforce(DEFAULT_SCOPE, FailureBehavior.FAIL_OPEN, FailureBehavior.FAIL_OPEN);

		assertThat(settings.actionFor(DecisionOutcome.ALLOW)).isEqualTo(EnforcementAction.PROCEED);
		assertThat(settings.actionFor(DecisionOutcome.DENY_POLICY)).isEqualTo(EnforcementAction.BLOCK);
		assertThat(settings.actionFor(DecisionOutcome.DENY_BUDGET)).isEqualTo(EnforcementAction.BLOCK);
		assertThat(settings.actionFor(DecisionOutcome.REQUIRE_APPROVAL))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
		assertThat(settings.actionFor(DecisionOutcome.INDETERMINATE))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
		assertThat(settings.actionFor(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
	}

	@Test
	void outageBlocksOnlyWhenCustomerChoosesFailClosedForOutages() {
		EnforcementSettings closedOnOutage =
			enforce(DEFAULT_SCOPE, FailureBehavior.FAIL_OPEN, FailureBehavior.FAIL_CLOSED);
		EnforcementSettings closedOnIndeterminate =
			enforce(DEFAULT_SCOPE, FailureBehavior.FAIL_CLOSED, FailureBehavior.FAIL_OPEN);

		assertThat(closedOnOutage.actionFor(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE))
			.isEqualTo(EnforcementAction.BLOCK);
		assertThat(closedOnOutage.actionFor(DecisionOutcome.INDETERMINATE))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
		assertThat(closedOnIndeterminate.actionFor(DecisionOutcome.INDETERMINATE))
			.isEqualTo(EnforcementAction.BLOCK);
		assertThat(closedOnIndeterminate.actionFor(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
	}

	@Test
	void enforcementScopeIsChosenByCustomer() {
		EnforcementSettings approvalOnly = enforce(
			EnumSet.of(DecisionOutcome.REQUIRE_APPROVAL),
			FailureBehavior.FAIL_OPEN,
			FailureBehavior.FAIL_OPEN
		);

		assertThat(approvalOnly.actionFor(DecisionOutcome.REQUIRE_APPROVAL))
			.isEqualTo(EnforcementAction.HOLD_FOR_APPROVAL);
		assertThat(approvalOnly.actionFor(DecisionOutcome.DENY_BUDGET))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
		assertThat(approvalOnly.actionFor(DecisionOutcome.DENY_POLICY))
			.isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
	}

	@ParameterizedTest
	@EnumSource(value = DecisionOutcome.class, names = {"ALLOW", "INDETERMINATE", "CONTROL_PLANE_UNAVAILABLE"})
	void scopeCannotContainNonPolicyOutcomes(DecisionOutcome outcome) {
		assertThatThrownBy(() -> enforce(EnumSet.of(outcome), FailureBehavior.FAIL_OPEN, FailureBehavior.FAIL_OPEN))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
