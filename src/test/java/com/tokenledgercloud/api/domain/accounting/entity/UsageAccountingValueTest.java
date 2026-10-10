package com.tokenledgercloud.api.domain.accounting.entity;

import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Kind.ACTUAL;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Kind.ESTIMATED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.PRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.UNPRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.NONE;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.PROVIDER_BILLING;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.SERVER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;

/** Spring 없이 팩토리 메서드의 검증 규칙만 확인하는 단위 테스트. */
class UsageAccountingValueTest {

    @Test
    void unpricedValueKeepsNullAmount() {
        // 가격을 모르는 값을 만든다.
        var amount = new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
        var value = UsageAccountingValue.of("event-1", 1, amount, null, null, "PRICE_MISSING", "SYSTEM", "ingest");
        // 금액은 0이 아니라 null이어야 한다.
        assertThat(value.getAmount()).isNull();
        assertThat(value.getPricingStatus()).isEqualTo(UNPRICED);
        assertThat(value.getId()).hasSize(36);
    }

    @Test
    void unpricedValueCannotReferenceSnapshot() {
        // UNPRICED인데 가격 스냅샷을 넘기면 거부해야 한다.
        var amount = new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
        assertThatThrownBy(() -> UsageAccountingValue.of("event-1", 1, amount, "snap-1", null, "R", "SYSTEM", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serverCatalogValueRequiresPricingSnapshot() {
        // SERVER_CATALOG 값은 스냅샷 id가 없으면 거부해야 한다.
        var amount = new AccountingAmount(ESTIMATED, PRICED, new BigDecimal("0.001000"), "USD", SERVER_CATALOG);
        assertThatThrownBy(() -> UsageAccountingValue.of("event-1", 1, amount, null, null, "R", "SYSTEM", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        // 스냅샷 id가 있으면 통과한다.
        assertThat(UsageAccountingValue.of("event-1", 1, amount, "snap-1", null, "R", "SYSTEM", "a").getPricingSnapshotId())
                .isEqualTo("snap-1");
    }

    @Test
    void providerBillingValueRequiresEvidence() {
        // PROVIDER_BILLING 값은 근거 식별자가 없으면 거부해야 한다.
        var amount = new AccountingAmount(ACTUAL, PRICED, new BigDecimal("1.250000"), "USD", PROVIDER_BILLING);
        assertThatThrownBy(() -> UsageAccountingValue.of("event-1", 1, amount, null, " ", "R", "SYSTEM", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        // 근거가 있으면 통과한다.
        assertThat(UsageAccountingValue.of("event-1", 1, amount, null, "inv-2026-10", "R", "SYSTEM", "a").getEvidenceReference())
                .isEqualTo("inv-2026-10");
    }

    @Test
    void revisionMustBePositive() {
        // 개정 번호가 0이면 거부해야 한다.
        var amount = new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
        assertThatThrownBy(() -> UsageAccountingValue.of("event-1", 0, amount, null, null, "R", "SYSTEM", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
