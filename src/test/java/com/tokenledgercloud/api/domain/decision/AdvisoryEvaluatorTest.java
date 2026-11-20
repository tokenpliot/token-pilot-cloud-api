package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.accounting.ServerPriceCalculator;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.Evaluation;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.Input;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.PriceInput;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.Scope;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.ScopeInput;
import com.tokenledgercloud.api.domain.policy.PolicyRules;

/** 판정 로직은 순수하므로 DB 없이 입력 → 결과만 확인한다. */
class AdvisoryEvaluatorTest {

	private static final long UNIT = 1_000_000L;
	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
	// 100만 토큰당 prompt=2, completion=8 (cache·reasoning은 같은 요율).
	private static final PriceInput PRICE = PriceInput.available(new ServerPriceCalculator.Rates(
		usd("2"), usd("8"), usd("2"), usd("8")), UNIT);

	private static BigDecimal usd(String v) {
		return new BigDecimal(v);
	}

	private static PolicyRules rules(String monthlySoft, String monthlyHard, String reqSoft, String reqHard, boolean active) {
		return new PolicyRules("USD", monthlySoft == null ? null : usd(monthlySoft), usd(monthlyHard),
			reqSoft == null ? null : usd(reqSoft), reqHard == null ? null : usd(reqHard), active, List.of());
	}

	private static ScopeInput project(PolicyRules rules, String spend) {
		return new ScopeInput(Scope.PROJECT, rules, rules == null ? null : "p:1", usd(spend));
	}

	private static ScopeInput env(PolicyRules rules, String spend) {
		return new ScopeInput(Scope.ENVIRONMENT, rules, rules == null ? null : "e:1", usd(spend));
	}

	// 입력 1000, 출력 500 토큰 -> 0.002 + 0.004 = 0.006 USD
	private static Input input(PriceInput price, ScopeInput... scopes) {
		return new Input(1000, 500, price, List.of(scopes), NOW);
	}

	@Test
	void withinLimitsIsAllowWithCostAndRemainingBudget() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "10", null, null, true), "3")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.reasons()).isEmpty();
		assertThat(e.requiredCostUsd()).isEqualByComparingTo("0.006");
		assertThat(e.remainingMonthlyBudgetUsd()).isEqualByComparingTo("7");
		assertThat(e.retryAt()).isNull();
		assertThat(e.algorithmVersion()).isEqualTo("advisory-v1");
	}

	@Test
	void sameInputGivesEqualResult() {
		Input in = input(PRICE, project(rules("5", "10", null, null, true), "4.999"), env(rules(null, "20", null, null, true), "1"));
		assertThat(AdvisoryEvaluator.evaluate(in)).isEqualTo(AdvisoryEvaluator.evaluate(in));
	}

	@Test
	void projectedCostOverMonthlyHardLimitIsDenyBudgetWithRetryAtNextUtcMonth() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "10", null, null, true), "9.995")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("MONTHLY_BUDGET_EXCEEDED");
		assertThat(e.remainingMonthlyBudgetUsd()).isEqualByComparingTo("0.005");
		assertThat(e.retryAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
	}

	@Test
	void landingExactlyOnTheHardLimitIsAllowed() {
		// 9.994 + 0.006 = 10.000
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "10", null, null, true), "9.994")));
		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
	}

	@Test
	void retryAtRollsOverTheYear() {
		Input in = new Input(1000, 500, PRICE, List.of(project(rules(null, "10", null, null, true), "10")),
			Instant.parse("2026-12-31T23:59:59Z"));
		assertThat(AdvisoryEvaluator.evaluate(in).retryAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
	}

	@Test
	void requestHardLimitDenyHasNoRetryTime() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "100", null, "0.005", true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("REQUEST_COST_LIMIT_EXCEEDED");
		assertThat(e.retryAt()).isNull();
	}

	@Test
	void requestWhoseCostAloneExceedsMonthlyLimitHasNoRetryTime() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "0.005", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(e.retryAt()).isNull();
	}

	@Test
	void softLimitsAreAllowWithWarningReasons() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules("3", "10", "0.001", "1", true), "3")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code)
			.containsExactly("REQUEST_COST_SOFT_LIMIT_EXCEEDED", "MONTHLY_SOFT_LIMIT_EXCEEDED");
	}

	@Test
	void environmentPolicyCannotRelaxProjectPolicy() {
		// 프로젝트 잔액은 부족하고 환경 한도는 넉넉하다 -> 프로젝트 한도가 이긴다.
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE,
			project(rules(null, "10", null, null, true), "9.999"),
			env(rules(null, "1000", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(e.reasons().get(0).message()).contains("project");
		assertThat(e.scopes()).extracting(AdvisoryEvaluator.ScopeResult::scope).containsExactly(Scope.PROJECT, Scope.ENVIRONMENT);
		assertThat(e.remainingMonthlyBudgetUsd()).isEqualByComparingTo("0.001");
	}

	@Test
	void inactiveScopeIsSkippedWhileTheOtherStillApplies() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE,
			project(rules(null, "0.001", null, null, false), "5"),
			env(rules(null, "10", null, null, true), "1")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.scopes()).extracting(AdvisoryEvaluator.ScopeResult::scope).containsExactly(Scope.ENVIRONMENT);
	}

	@Test
	void allPoliciesInactiveIsAllowNotIndeterminate() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules(null, "0.001", null, null, false), "5")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("POLICY_INACTIVE");
		assertThat(e.remainingMonthlyBudgetUsd()).isNull();
	}

	@Test
	void allPoliciesInactiveIsAllowEvenWhenPriceIsUnknown() {
		// 정책을 끈 fail-closed 고객이 가격 미상 때문에 계속 막히면 안 된다.
		Evaluation e = AdvisoryEvaluator.evaluate(input(PriceInput.unavailable("PRICING_NOT_FOUND"),
			project(rules(null, "0.001", null, null, false), "5")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("POLICY_INACTIVE");
		assertThat(e.requiredCostUsd()).isNull();
	}

	@Test
	void noPolicyIsIndeterminate() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(null, "0"), env(null, "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("POLICY_NOT_CONFIGURED");
	}

	@Test
	void missingPriceIsIndeterminateNeverZeroCost() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PriceInput.unavailable("PRICING_NOT_FOUND"),
			project(rules(null, "10", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("PRICING_NOT_FOUND");
		assertThat(e.requiredCostUsd()).isNull();
	}

	@Test
	void unconfirmedRateUnitIsIndeterminate() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PriceInput.available(new ServerPriceCalculator.Rates(
			usd("2"), usd("8"), usd("2"), usd("8")), 0L), project(rules(null, "10", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("PRICING_UNAVAILABLE");
	}

	@Test
	void missingPriceAndMissingPolicyReportBothReasons() {
		Evaluation e = AdvisoryEvaluator.evaluate(input(PriceInput.unavailable("PRICING_NOT_FOUND"), project(null, "0")));

		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code)
			.containsExactly("PRICING_NOT_FOUND", "POLICY_NOT_CONFIGURED");
	}

	@Test
	void costIsAnUpperBoundUsingTheMoreExpensiveCacheAndReasoningRates() {
		// cache·reasoning 요율이 더 비싼 모델: 입력은 3, 출력은 12 요율로 계산한다. 1000*3 + 500*12 = 9000 -> 0.009
		var pricey = PriceInput.available(new ServerPriceCalculator.Rates(usd("2"), usd("8"), usd("3"), usd("12")), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(input(pricey, project(rules(null, "10", null, null, true), "0")));

		assertThat(e.requiredCostUsd()).isEqualByComparingTo("0.009");
	}

	@Test
	void cheaperCacheRateDoesNotLowerTheBound() {
		var cheapCache = PriceInput.available(new ServerPriceCalculator.Rates(usd("2"), usd("8"), usd("0.5"), usd("8")), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(input(cheapCache, project(rules(null, "10", null, null, true), "0")));

		assertThat(e.requiredCostUsd()).isEqualByComparingTo("0.006");
	}

	@Test
	void anyUnresolvableDenialReasonClearsRetryAt() {
		// 프로젝트는 월 한도(기다리면 풀림), 환경은 요청당 비용 한도(안 풀림) -> null
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE,
			project(rules(null, "10", null, null, true), "9.999"),
			env(rules(null, "100", null, "0.001", true), "0")));
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code)
			.containsExactly("MONTHLY_BUDGET_EXCEEDED", "REQUEST_COST_LIMIT_EXCEEDED");
		assertThat(e.retryAt()).isNull();

		// 프로젝트는 월 한도(풀림), 환경은 이번 요청 비용만으로 월 한도 초과(안 풀림) -> null
		Evaluation f = AdvisoryEvaluator.evaluate(input(PRICE,
			project(rules(null, "10", null, null, true), "9.999"),
			env(rules(null, "0.005", null, null, true), "0")));
		assertThat(f.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(f.retryAt()).isNull();
	}

	@Test
	void costIsRoundedUpNotHalfEven() {
		// 1 토큰 * 0.5 / 1,000,000 = 0.0000005 -> 올림 0.000001 (HALF_EVEN이면 0.000000)
		var tiny = PriceInput.available(new ServerPriceCalculator.Rates(usd("0.5"), usd("0.5"), null, null), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(new Input(1, 0, tiny, List.of(project(rules(null, "10", null, null, true), "0")), NOW));

		assertThat(e.requiredCostUsd()).isEqualByComparingTo("0.000001");
		assertThat(e.requiredCostUsd().scale()).isEqualTo(6);
	}

	@Test
	void roundedUpCostCannotSlipPastTheLimitOnTheBoundary() {
		// 실제 비용 0.0000005는 HALF_EVEN이면 0으로 내려가 한도 0을 통과한다. 올림하면 0.000001 > 0 이라 거부한다.
		var tiny = PriceInput.available(new ServerPriceCalculator.Rates(usd("0.5"), usd("0.5"), null, null), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(new Input(1, 0, tiny, List.of(project(rules(null, "0", null, null, true), "0")), NOW));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
	}

	@Test
	void nullCacheAndReasoningRatesFallBackToBaseRates() {
		var noExtras = PriceInput.available(new ServerPriceCalculator.Rates(usd("2"), usd("8"), null, null), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(input(noExtras, project(rules(null, "10", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(e.requiredCostUsd()).isEqualByComparingTo("0.006");
	}

	@Test
	void missingBaseRateIsIndeterminate() {
		var noCompletion = PriceInput.available(new ServerPriceCalculator.Rates(usd("2"), null, null, null), UNIT);
		Evaluation e = AdvisoryEvaluator.evaluate(input(noCompletion, project(rules(null, "10", null, null, true), "0")));

		assertThat(e.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(e.reasons()).extracting(AdvisoryEvaluator.Reason::code).containsExactly("PRICING_UNAVAILABLE");
	}

	@Test
	void softLimitUsesTheSameStrictComparison() {
		// 누적 + 비용 == soft 한도(3.000) 이면 경고 없음
		Evaluation e = AdvisoryEvaluator.evaluate(input(PRICE, project(rules("3", "10", null, null, true), "2.994")));
		assertThat(e.reasons()).isEmpty();
	}

	@Test
	void rejectsNegativeTokensAndDuplicateScopes() {
		assertThatThrownBy(() -> new Input(-1, 0, PRICE, List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Input(0, 0, PRICE,
			List.of(project(null, "0"), project(null, "0")), NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> project(rules(null, "10", null, null, true), "-1")).isInstanceOf(IllegalArgumentException.class);
	}
}
