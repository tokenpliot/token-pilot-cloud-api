package com.tokenledgercloud.api.domain.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 비용 계산 규칙(설계 문서)을 확인하는 단위 테스트. */
class ServerPriceCalculatorTest {

	// 100만 토큰당 요율. prompt=2, completion=8, cache=0.5, reasoning=8 (USD).
	private static final long UNIT = 1_000_000L;
	private static final ServerPriceCalculator.Rates RATES = new ServerPriceCalculator.Rates(
			new BigDecimal("2.000000"), new BigDecimal("8.000000"),
			new BigDecimal("0.500000"), new BigDecimal("8.000000"));

	@Test
	void designDocumentExampleIsPricedAt0_00515() {
		// 설계 문서의 예: prompt=1200, completion=400, cache=300, reasoning=100 -> 0.00515달러.
		var result = ServerPriceCalculator.calculate(new UsageTokens(1200, 400, 300L, 100L), RATES, UNIT);
		assertThat(result.isPriced()).isTrue();
		assertThat(result.amount()).isEqualByComparingTo("0.005150");
		assertThat(result.amount().scale()).isEqualTo(6);
	}

	@Test
	void unconfirmedRateUnitIsUnpricedNotZero() {
		// 요율 단위를 확인하지 못했으면 가격 미상이다. 금액이 0이 아니라 null이어야 한다.
		var result = ServerPriceCalculator.calculate(new UsageTokens(1200, 400, 300L, 100L), RATES, 0L);
		assertThat(result.isPriced()).isFalse();
		assertThat(result.amount()).isNull();
		assertThat(result.reasonCode()).isEqualTo("RATE_UNIT_UNCONFIRMED");
	}

	@Test
	void unknownCacheDetailIsPricedOnlyWhenCacheRateEqualsPromptRate() {
		// 캐시 토큰을 모르고 캐시 요율이 프롬프트 요율과 다르면 가격 미상이다.
		var different = ServerPriceCalculator.calculate(new UsageTokens(1000, 0, null, 0L), RATES, UNIT);
		assertThat(different.isPriced()).isFalse();
		assertThat(different.reasonCode()).isEqualTo("CACHE_DETAIL_UNKNOWN");
		// 캐시 요율이 프롬프트 요율과 같으면 부모 합계로 계산할 수 있다.
		var sameRates = new ServerPriceCalculator.Rates(new BigDecimal("2.000000"), new BigDecimal("8.000000"),
				new BigDecimal("2.000000"), new BigDecimal("8.000000"));
		var same = ServerPriceCalculator.calculate(new UsageTokens(1000, 0, null, 0L), sameRates, UNIT);
		assertThat(same.amount()).isEqualByComparingTo("0.002000");
	}

	@Test
	void cachedTokensWithZeroCacheRateAreUnpriced() {
		// 캐시 토큰이 있는데 캐시 요율이 0이면 무료인지 미상인지 알 수 없으므로 가격 미상이다.
		var zeroCache = new ServerPriceCalculator.Rates(new BigDecimal("2.000000"), new BigDecimal("8.000000"),
				BigDecimal.ZERO, new BigDecimal("8.000000"));
		var result = ServerPriceCalculator.calculate(new UsageTokens(1000, 0, 100L, 0L), zeroCache, UNIT);
		assertThat(result.isPriced()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("CACHE_RATE_UNCONFIRMED");
	}

	@Test
	void reasoningTokensWithZeroReasoningRateAreUnpriced() {
		// 추론 토큰이 있는데 추론 요율이 0이면 가격 미상이다.
		var zeroReasoning = new ServerPriceCalculator.Rates(new BigDecimal("2.000000"), new BigDecimal("8.000000"),
				new BigDecimal("0.500000"), BigDecimal.ZERO);
		var result = ServerPriceCalculator.calculate(new UsageTokens(0, 100, 0L, 50L), zeroReasoning, UNIT);
		assertThat(result.isPriced()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("REASONING_RATE_UNCONFIRMED");
	}

	@Test
	void zeroDetailTokensNeedNoDetailRates() {
		// 상세 토큰이 0으로 확인되었으면 상세 요율이 0이어도 부모 요율로 계산한다.
		var zeroDetailRates = new ServerPriceCalculator.Rates(new BigDecimal("2.000000"), new BigDecimal("8.000000"),
				BigDecimal.ZERO, BigDecimal.ZERO);
		var result = ServerPriceCalculator.calculate(new UsageTokens(500, 500, 0L, 0L), zeroDetailRates, UNIT);
		// (500 * 2 + 500 * 8) / 1,000,000 = 0.005
		assertThat(result.amount()).isEqualByComparingTo("0.005000");
	}

	@Test
	void missingRequiredRatesAreUnpriced() {
		// 프롬프트나 응답 요율이 없으면 가격 미상이다.
		var missing = new ServerPriceCalculator.Rates(null, new BigDecimal("8.000000"), BigDecimal.ONE, BigDecimal.ONE);
		var result = ServerPriceCalculator.calculate(new UsageTokens(1, 1, 0L, 0L), missing, UNIT);
		assertThat(result.isPriced()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("RATE_MISSING");
	}

	@Test
	void roundsOnceAtTheEndWithHalfEven() {
		// 합계 2.5 / 1,000,000 = 0.0000025 -> 소수 6자리 HALF_EVEN으로 0.000002 (짝수 쪽).
		var tiny = new ServerPriceCalculator.Rates(new BigDecimal("2.500000"), new BigDecimal("0.000000"),
				new BigDecimal("2.500000"), new BigDecimal("0.000000"));
		var result = ServerPriceCalculator.calculate(new UsageTokens(1, 0, 0L, 0L), tiny, UNIT);
		assertThat(result.amount()).isEqualByComparingTo("0.000002");
	}
}
