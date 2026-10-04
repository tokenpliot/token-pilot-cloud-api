package com.tokenledgercloud.api.domain.accounting;

import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Kind.*;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.*;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AccountingContractTest {
    @Test
    void actualUsageCanBeUnpricedAndIsDifferentFromFreeUsage() {
        var unknown = new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
        var free = new AccountingAmount(ACTUAL, PRICED, BigDecimal.ZERO, "USD", SERVER_CATALOG);
        var estimate = new AccountingAmount(ESTIMATED, PRICED, new BigDecimal("0.000001"), "USD", SERVER_CATALOG);
        assertThat(unknown.amount()).isNull();
        assertThat(free.amount()).isEqualByComparingTo("0");
        assertThat(estimate.kind()).isNotEqualTo(free.kind());
        assertThatThrownBy(() -> new AccountingAmount(ACTUAL, UNPRICED, BigDecimal.ZERO, "USD", NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesMicrousdAndRejectsSilentRoundingOverflowAndCurrencyMixing() {
        assertThat(new AccountingAmount(ACTUAL, PRICED, new BigDecimal("999999999999.999999"), "USD", PROVIDER_BILLING).amount())
                .isEqualByComparingTo("999999999999.999999");
        assertThatThrownBy(() -> new AccountingAmount(ACTUAL, PRICED, new BigDecimal("0.0000001"), "USD", SERVER_CATALOG))
                .isInstanceOf(ArithmeticException.class);
        for (String value : new String[] {"1000000000000", "-0.000001"}) {
            assertThatThrownBy(() -> new AccountingAmount(ACTUAL, PRICED, new BigDecimal(value), "USD", SERVER_CATALOG))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AccountingAmount(ACTUAL, PRICED, BigDecimal.ONE, "KRW", SERVER_CATALOG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountingAmount(ESTIMATED, PRICED, BigDecimal.ONE, "USD", PROVIDER_BILLING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountingAmount(ACTUAL, PRICED, BigDecimal.ONE, "USD", NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cacheAndReasoningAreNotCountedTwice() {
        assertThat(new UsageTokens(1200, 400, 300L, 100L).totalTokens()).isEqualTo(1600);
        var unknownDetails = new UsageTokens(1200, 400, null, null);
        assertThat(unknownDetails.cachedPromptTokens()).isNull();
        assertThat(unknownDetails.totalTokens()).isEqualTo(1600);
        assertThatThrownBy(() -> new UsageTokens(1200, 400, 1201L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageTokens(1200, 400, 0L, 401L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageTokens(1, 1, -1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageTokens(Long.MAX_VALUE, 1, null, null)).isInstanceOf(ArithmeticException.class);
    }
}
