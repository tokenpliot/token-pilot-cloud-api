package com.tokenledgercloud.api.domain.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 토큰 수와 요율로 비용을 계산하는 순수 함수 모음. 설계 문서(이슈 #9)의 비용 계산 규칙을 따른다.
 * 확신할 수 없는 경우에는 0달러로 계산하지 않고 "가격 미상"(금액 없음)으로 돌려준다.
 */
public final class ServerPriceCalculator {

	// 유틸리티 클래스이므로 인스턴스 생성을 막는다.
	private ServerPriceCalculator() {
	}

	/** 가격표의 요율. 각 요율은 rateUnitTokens 토큰당 가격이다. */
	public record Rates(BigDecimal prompt, BigDecimal completion, BigDecimal cachedPrompt, BigDecimal reasoning) {
	}

	/** 계산 결과. 금액이 null이면 가격 미상이고, reasonCode가 이유를 알려준다. */
	public record Result(BigDecimal amount, String reasonCode) {

		// 가격이 계산된 결과를 만든다.
		static Result priced(BigDecimal amount) {
			return new Result(amount, "PRICED_BY_CATALOG");
		}

		// 가격을 알 수 없는 결과를 만든다. 금액은 0이 아니라 null이다.
		static Result unpriced(String reasonCode) {
			return new Result(null, reasonCode);
		}

		// 가격이 계산되었는지 여부.
		public boolean isPriced() {
			return amount != null;
		}
	}

	/**
	 * 비용을 계산한다. 비용 버킷은 서로 겹치지 않는다.
	 * (prompt - cache) * promptRate + cache * cacheRate + (completion - reasoning) * completionRate + reasoning * reasoningRate
	 * 중간 계산은 반올림하지 않고, 합계를 단위로 나눈 뒤 마지막에 한 번만 소수 6자리로 HALF_EVEN 반올림한다.
	 *
	 * @param rateUnitTokens 요율의 토큰 단위. 0 이하면 확인되지 않은 것으로 보고 가격 미상으로 처리한다.
	 */
	public static Result calculate(UsageTokens tokens, Rates rates, long rateUnitTokens) {
		// 입력 객체는 필수다.
		Objects.requireNonNull(tokens, "tokens");
		Objects.requireNonNull(rates, "rates");
		// 요율 단위를 확인하지 못했으면 계산하지 않는다.
		if (rateUnitTokens <= 0) {
			return Result.unpriced("RATE_UNIT_UNCONFIRMED");
		}
		// 프롬프트와 응답 요율은 필수이고 음수일 수 없다.
		BigDecimal promptRate = rates.prompt();
		BigDecimal completionRate = rates.completion();
		if (promptRate == null || completionRate == null || promptRate.signum() < 0 || completionRate.signum() < 0) {
			return Result.unpriced("RATE_MISSING");
		}

		// 캐시 버킷: 프롬프트 토큰 중 캐시된 부분.
		long cached;
		BigDecimal cachedRate;
		Long cachedTokens = tokens.cachedPromptTokens();
		if (cachedTokens == null) {
			// 캐시 토큰을 모르면, 캐시 요율이 프롬프트 요율과 같을 때만 부모 합계로 계산할 수 있다.
			if (rates.cachedPrompt() == null || rates.cachedPrompt().compareTo(promptRate) != 0) {
				return Result.unpriced("CACHE_DETAIL_UNKNOWN");
			}
			cached = 0;
			cachedRate = promptRate;
		} else if (cachedTokens > 0) {
			// 캐시 토큰이 있는데 캐시 요율이 없거나 0이면 무료인지 미상인지 알 수 없다.
			if (rates.cachedPrompt() == null || rates.cachedPrompt().signum() <= 0) {
				return Result.unpriced("CACHE_RATE_UNCONFIRMED");
			}
			cached = cachedTokens;
			cachedRate = rates.cachedPrompt();
		} else {
			cached = 0;
			cachedRate = promptRate;
		}

		// 추론 버킷: 응답 토큰 중 추론에 쓰인 부분.
		long reasoning;
		BigDecimal reasoningRate;
		Long reasoningTokens = tokens.reasoningTokens();
		if (reasoningTokens == null) {
			// 추론 토큰을 모르면, 추론 요율이 응답 요율과 같을 때만 부모 합계로 계산할 수 있다.
			if (rates.reasoning() == null || rates.reasoning().compareTo(completionRate) != 0) {
				return Result.unpriced("REASONING_DETAIL_UNKNOWN");
			}
			reasoning = 0;
			reasoningRate = completionRate;
		} else if (reasoningTokens > 0) {
			// 추론 토큰이 있는데 추론 요율이 없거나 0이면 무료인지 미상인지 알 수 없다.
			if (rates.reasoning() == null || rates.reasoning().signum() <= 0) {
				return Result.unpriced("REASONING_RATE_UNCONFIRMED");
			}
			reasoning = reasoningTokens;
			reasoningRate = rates.reasoning();
		} else {
			reasoning = 0;
			reasoningRate = completionRate;
		}

		// 네 버킷의 (토큰 수 * 요율)을 반올림 없이 더한다.
		BigDecimal total = BigDecimal.valueOf(Math.subtractExact(tokens.promptTokens(), cached)).multiply(promptRate)
				.add(BigDecimal.valueOf(cached).multiply(cachedRate))
				.add(BigDecimal.valueOf(Math.subtractExact(tokens.completionTokens(), reasoning)).multiply(completionRate))
				.add(BigDecimal.valueOf(reasoning).multiply(reasoningRate));
		// 합계를 단위로 나누고, 마지막에 한 번만 소수 6자리로 반올림한다.
		return Result.priced(total.divide(BigDecimal.valueOf(rateUnitTokens), 6, RoundingMode.HALF_EVEN));
	}
}
