package com.tokenledgercloud.api.domain.accounting;

import java.util.Objects;

/**
 * 이벤트의 상태 컬럼들과 최신 ACTUAL 금액으로 ReconciliationState를 계산하는 순수 함수 모음.
 * DB나 Spring에 의존하지 않으므로 단위 테스트가 쉽다.
 */
public final class ReconciliationClassifier {

    // 유틸리티 클래스이므로 인스턴스 생성을 막는다.
    private ReconciliationClassifier() {
    }

    /**
     * @param accountingStatus 이벤트의 회계 검증 상태
     * @param callStatus       이벤트의 호출 결과 상태
     * @param settlementState  이벤트의 정산 상태
     * @param latestActual     이벤트의 최신 ACTUAL 금액. 아직 없으면 null
     */
    public static ReconciliationState classify(AccountingStatus accountingStatus,
                                               CallStatus callStatus,
                                               SettlementState settlementState,
                                               AccountingAmount latestActual) {
        // 입력 상태값이 null이면 잘못된 호출이므로 즉시 실패시킨다.
        Objects.requireNonNull(accountingStatus, "accountingStatus");
        Objects.requireNonNull(callStatus, "callStatus");
        Objects.requireNonNull(settlementState, "settlementState");

        // 기존(V6 이전) 행은 원장 대상이 아니므로 정산 확인 대상에서 뺀다.
        if (accountingStatus == AccountingStatus.LEGACY_UNVERIFIED) {
            return ReconciliationState.NONE;
        }
        // 이미 정산이 끝난 행은 더 확인할 것이 없다.
        if (settlementState == SettlementState.SETTLED) {
            return ReconciliationState.NONE;
        }
        // 격리된 행은 사람이 대조해야 한다.
        if (accountingStatus == AccountingStatus.QUARANTINED) {
            return ReconciliationState.RECONCILIATION_REQUIRED;
        }
        // 사용량을 알 수 없는 호출도 사람이 대조해야 한다.
        if (callStatus == CallStatus.USAGE_UNKNOWN) {
            return ReconciliationState.RECONCILIATION_REQUIRED;
        }
        // 실제 사용량은 있는데 금액이 비어 있으면 UNPRICED. 0달러로 취급하면 안 된다.
        if (latestActual != null
                && latestActual.pricingStatus() == AccountingAmount.PricingStatus.UNPRICED) {
            return ReconciliationState.UNPRICED;
        }
        // 위 어디에도 해당하지 않으면 아직 대기 중이거나 정상이다.
        return ReconciliationState.NONE;
    }
}
