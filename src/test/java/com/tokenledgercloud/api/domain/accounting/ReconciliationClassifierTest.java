package com.tokenledgercloud.api.domain.accounting;

import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Kind.ACTUAL;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.PRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.UNPRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.NONE;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.SERVER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ReconciliationClassifierTest {

    // 가격을 찾지 못한 실제 사용량(금액 null)
    private static final AccountingAmount UNPRICED_ACTUAL =
            new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
    // 가격이 확정된 실제 사용량
    private static final AccountingAmount PRICED_ACTUAL =
            new AccountingAmount(ACTUAL, PRICED, new BigDecimal("0.005150"), "USD", SERVER_CATALOG);

    @Test
    void legacyRowsAreNeverFlagged() {
        // 기존 행은 UNPRICED 금액이 붙어 있어도 NONE이어야 한다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.LEGACY_UNVERIFIED,
                CallStatus.LEGACY_UNKNOWN, SettlementState.UNSETTLED, UNPRICED_ACTUAL))
                .isEqualTo(ReconciliationState.NONE);
    }

    @Test
    void settledRowsNeedNothing() {
        // 정산 완료 행은 격리 상태여도 더 확인할 것이 없다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.QUARANTINED,
                CallStatus.SUCCEEDED, SettlementState.SETTLED, PRICED_ACTUAL))
                .isEqualTo(ReconciliationState.NONE);
    }

    @Test
    void quarantinedRowsRequireReconciliation() {
        // 격리된 미정산 행은 대조가 필요하다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.QUARANTINED,
                CallStatus.SUCCEEDED, SettlementState.UNSETTLED, null))
                .isEqualTo(ReconciliationState.RECONCILIATION_REQUIRED);
    }

    @Test
    void unknownUsageRequiresReconciliation() {
        // 사용량을 모르는 호출은 대조가 필요하다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.PENDING,
                CallStatus.USAGE_UNKNOWN, SettlementState.UNSETTLED, null))
                .isEqualTo(ReconciliationState.RECONCILIATION_REQUIRED);
    }

    @Test
    void unpricedActualIsReportedAsUnpricedNotZero() {
        // 가격 누락은 UNPRICED로 보고되어야 한다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.PENDING,
                CallStatus.SUCCEEDED, SettlementState.UNSETTLED, UNPRICED_ACTUAL))
                .isEqualTo(ReconciliationState.UNPRICED);
    }

    @Test
    void pricedOrWaitingRowsAreNone() {
        // 가격이 확정된 행과 아직 ACTUAL이 없는 행은 NONE이다.
        assertThat(ReconciliationClassifier.classify(AccountingStatus.VERIFIED,
                CallStatus.SUCCEEDED, SettlementState.UNSETTLED, PRICED_ACTUAL))
                .isEqualTo(ReconciliationState.NONE);
        assertThat(ReconciliationClassifier.classify(AccountingStatus.PENDING,
                CallStatus.SUCCEEDED, SettlementState.UNSETTLED, null))
                .isEqualTo(ReconciliationState.NONE);
    }

    @Test
    void nullStatusIsRejected() {
        // 상태값이 null이면 NullPointerException으로 즉시 실패해야 한다.
        assertThatThrownBy(() -> ReconciliationClassifier.classify(null,
                CallStatus.SUCCEEDED, SettlementState.UNSETTLED, null))
                .isInstanceOf(NullPointerException.class);
    }
}
