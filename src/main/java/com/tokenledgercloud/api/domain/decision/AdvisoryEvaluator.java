package com.tokenledgercloud.api.domain.decision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.tokenledgercloud.api.domain.accounting.ServerPriceCalculator;
import com.tokenledgercloud.api.domain.accounting.UsageTokens;
import com.tokenledgercloud.api.domain.policy.PolicyRules;

/**
 * Advisory 판정의 순수 로직. DB·시계·네트워크를 읽지 않고, 입력이 같으면 결과가 같다.
 * 이 결과는 서버 내부 모델이다. HTTP 응답 모양(필드 이름, 정책 버전 표기)은 응답 매핑 단계에서 정한다.
 *
 * <p>규칙은 PolicyRules를 따른다: 활성인 모든 범위를 함께 만족해야 하고, 환경 정책이 프로젝트 정책을 완화하지 않는다.
 * 판정은 권고일 뿐 호출을 막지 않는다.
 */
public final class AdvisoryEvaluator {

	public static final String ALGORITHM_VERSION = "advisory-v1";

	private AdvisoryEvaluator() {
	}

	public enum Scope {
		PROJECT,
		ENVIRONMENT
	}

	/**
	 * 한 범위의 정책 입력.
	 *
	 * @param rules         그 범위의 정책 규칙. 정책이 아예 없으면 null
	 * @param policyVersion 그 범위의 정책 버전({@code <policyId>:<revision>}). rules가 null이면 null
	 * @param monthSpendUsd 이 범위의 UTC 월 누적 확정 지출. 지출이 없으면 0
	 */
	public record ScopeInput(Scope scope, PolicyRules rules, String policyVersion, BigDecimal monthSpendUsd) {
		public ScopeInput {
			Objects.requireNonNull(scope, "scope");
			Objects.requireNonNull(monthSpendUsd, "monthSpendUsd");
			if (monthSpendUsd.signum() < 0) {
				throw new IllegalArgumentException("monthSpendUsd must be nonnegative");
			}
			if (rules != null && policyVersion == null) {
				throw new IllegalArgumentException("policyVersion is required when rules are present");
			}
		}
	}

	/**
	 * 가격 입력. 가격표를 못 찾았거나 단위를 확인하지 못했으면 {@link #unavailable}을 쓴다.
	 *
	 * @param rates          rateUnitTokens 토큰당 요율. unavailable이면 null
	 * @param rateUnitTokens 요율의 토큰 단위
	 * @param unavailableCode rates가 없을 때의 사유 코드. 있으면 null
	 */
	public record PriceInput(ServerPriceCalculator.Rates rates, long rateUnitTokens, String unavailableCode) {
		public static PriceInput available(ServerPriceCalculator.Rates rates, long rateUnitTokens) {
			return new PriceInput(Objects.requireNonNull(rates, "rates"), rateUnitTokens, null);
		}

		public static PriceInput unavailable(String reasonCode) {
			return new PriceInput(null, 0, Objects.requireNonNull(reasonCode, "reasonCode"));
		}
	}

	/**
	 * @param estimatedInputTokens 입력 토큰 추정치
	 * @param maxOutputTokens      출력 토큰 상한(보수적 상한)
	 * @param now                  월 경계와 재시도 시각 계산에만 쓰는 기준 시각
	 */
	public record Input(long estimatedInputTokens, long maxOutputTokens, PriceInput price,
						List<ScopeInput> scopes, Instant now) {
		public Input {
			if (estimatedInputTokens < 0 || maxOutputTokens < 0) {
				throw new IllegalArgumentException("Token counts must be nonnegative");
			}
			Objects.requireNonNull(price, "price");
			Objects.requireNonNull(now, "now");
			scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
			if (scopes.stream().map(ScopeInput::scope).distinct().count() != scopes.size()) {
				throw new IllegalArgumentException("Each scope may appear at most once");
			}
		}
	}

	public record Reason(String code, String message) {
	}

	/**
	 * 평가에 쓰인 범위 하나의 결과.
	 *
	 * @param remainingMonthlyBudgetUsd 이번 요청 전 기준의 월 hard 한도 잔액(음수면 0)
	 */
	public record ScopeResult(Scope scope, String policyVersion, BigDecimal monthlyHardLimitUsd,
							  BigDecimal monthSpendUsd, BigDecimal remainingMonthlyBudgetUsd) {
	}

	/**
	 * @param requiredCostUsd            이번 요청의 최대 예상 비용(보수적 상한). 가격을 알 수 없으면 null
	 * @param remainingMonthlyBudgetUsd  평가된 범위 중 가장 작은 월 한도 잔액. 평가된 범위가 없으면 null
	 * @param scopes                     실제로 평가한 활성 범위(프로젝트, 환경 순)
	 * @param retryAfter                 월 한도 때문에 거부 권고일 때, 한도가 초기화되어 다시 시도해 볼 수 있는 시각(다음 UTC 월 1일 00:00).
	 *                                   이번 요청 비용만으로 한도를 넘거나 요청당 한도 때문이면 기다려도 달라지지 않으므로 null
	 */
	public record Evaluation(DecisionOutcome outcome, List<Reason> reasons, BigDecimal requiredCostUsd,
							 BigDecimal remainingMonthlyBudgetUsd, List<ScopeResult> scopes, Instant retryAfter,
							 String algorithmVersion) {
	}

	public static Evaluation evaluate(Input input) {
		List<Reason> indeterminate = new ArrayList<>();

		BigDecimal cost = null;
		if (input.price().rates() == null) {
			indeterminate.add(new Reason(priceReasonCode(input.price().unavailableCode()),
				"Maximum cost cannot be computed: " + input.price().unavailableCode() + "."));
		} else {
			var priced = upperBoundCost(input);
			if (priced.isPriced()) {
				cost = priced.amount();
			} else {
				indeterminate.add(new Reason(priceReasonCode(priced.reasonCode()),
					"Maximum cost cannot be computed: " + priced.reasonCode() + "."));
			}
		}

		List<ScopeInput> configured = input.scopes().stream()
			.filter(s -> s.rules() != null)
			.sorted(java.util.Comparator.comparing(ScopeInput::scope))
			.toList();
		if (configured.isEmpty()) {
			indeterminate.add(new Reason("POLICY_NOT_CONFIGURED", "No policy is configured for this project or environment."));
		}
		if (!indeterminate.isEmpty()) {
			return new Evaluation(DecisionOutcome.INDETERMINATE, List.copyOf(indeterminate), cost, null,
				List.of(), null, ALGORITHM_VERSION);
		}

		List<ScopeInput> active = configured.stream().filter(s -> Boolean.TRUE.equals(s.rules().active())).toList();
		if (active.isEmpty()) {
			// 고객이 정책을 끈 것이다. 판정 근거가 없는 것이 아니라 적용할 규칙이 없는 것이므로 판정 불가로 보지 않는다.
			return new Evaluation(DecisionOutcome.ALLOW,
				List.of(new Reason("POLICY_INACTIVE", "All applicable policies are inactive; no rule was evaluated.")),
				cost, null, List.of(), null, ALGORITHM_VERSION);
		}

		List<Reason> denials = new ArrayList<>();
		List<Reason> warnings = new ArrayList<>();
		List<ScopeResult> results = new ArrayList<>();
		boolean monthlyDenied = false;
		boolean costAloneExceedsMonthly = false;
		BigDecimal remaining = null;

		for (ScopeInput scope : active) {
			PolicyRules rules = scope.rules();
			String label = scope.scope().name().toLowerCase();

			if (rules.requestHardLimitUsd() != null && cost.compareTo(rules.requestHardLimitUsd()) > 0) {
				denials.add(new Reason("REQUEST_LIMIT_EXCEEDED",
					"Maximum request cost exceeds the " + label + " per-request hard limit."));
			} else if (rules.requestSoftLimitUsd() != null && cost.compareTo(rules.requestSoftLimitUsd()) > 0) {
				warnings.add(new Reason("REQUEST_SOFT_LIMIT_EXCEEDED",
					"Maximum request cost exceeds the " + label + " per-request soft limit."));
			}

			BigDecimal projected = scope.monthSpendUsd().add(cost);
			if (projected.compareTo(rules.monthlyHardLimitUsd()) > 0) {
				denials.add(new Reason("MONTHLY_BUDGET_EXCEEDED",
					"Projected cost exceeds the remaining " + label + " monthly budget."));
				monthlyDenied = true;
				if (cost.compareTo(rules.monthlyHardLimitUsd()) > 0) {
					costAloneExceedsMonthly = true;
				}
			} else if (rules.monthlySoftLimitUsd() != null && projected.compareTo(rules.monthlySoftLimitUsd()) > 0) {
				warnings.add(new Reason("MONTHLY_SOFT_LIMIT_EXCEEDED",
					"Projected cost exceeds the " + label + " monthly soft limit."));
			}

			BigDecimal scopeRemaining = rules.monthlyHardLimitUsd().subtract(scope.monthSpendUsd()).max(BigDecimal.ZERO)
				.setScale(6);
			results.add(new ScopeResult(scope.scope(), scope.policyVersion(), rules.monthlyHardLimitUsd(),
				scope.monthSpendUsd(), scopeRemaining));
			remaining = remaining == null ? scopeRemaining : remaining.min(scopeRemaining);
		}

		if (!denials.isEmpty()) {
			Instant retry = monthlyDenied && !costAloneExceedsMonthly && denials.stream()
				.noneMatch(r -> r.code().equals("REQUEST_LIMIT_EXCEEDED")) ? nextMonthStart(input.now()) : null;
			return new Evaluation(DecisionOutcome.DENY_BUDGET, List.copyOf(denials), cost, remaining,
				List.copyOf(results), retry, ALGORITHM_VERSION);
		}
		return new Evaluation(DecisionOutcome.ALLOW, List.copyOf(warnings), cost, remaining,
			List.copyOf(results), null, ALGORITHM_VERSION);
	}

	/**
	 * 보수적 상한: 입력은 prompt·cachedPrompt 중 비싼 요율, 출력은 completion·reasoning 중 비싼 요율로 모두 계산한다.
	 * 캐시·추론 세부는 모르는 값이므로 0으로 두고, 같은 요율의 두 버킷으로 계산기를 재사용한다.
	 */
	private static ServerPriceCalculator.Result upperBoundCost(Input input) {
		ServerPriceCalculator.Rates r = input.price().rates();
		BigDecimal promptRate = r.prompt();
		BigDecimal completionRate = r.completion();
		if (promptRate != null && r.cachedPrompt() != null) {
			promptRate = promptRate.max(r.cachedPrompt());
		}
		if (completionRate != null && r.reasoning() != null) {
			completionRate = completionRate.max(r.reasoning());
		}
		var bounded = new ServerPriceCalculator.Rates(promptRate, completionRate, promptRate, completionRate);
		return ServerPriceCalculator.calculate(
			new UsageTokens(input.estimatedInputTokens(), input.maxOutputTokens(), 0L, 0L),
			bounded, input.price().rateUnitTokens());
	}

	private static String priceReasonCode(String detail) {
		return "PRICING_NOT_FOUND".equals(detail) ? "PRICING_NOT_FOUND" : "PRICING_UNAVAILABLE";
	}

	private static Instant nextMonthStart(Instant now) {
		return now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).plusMonths(1)
			.atStartOfDay(ZoneOffset.UTC).toInstant();
	}
}
