package com.tokenledgercloud.api.domain.accounting.observe.dto;

import java.time.LocalDateTime;

/**
 * 정산 확인 현황 응답. 아직 정산되지 않은 이벤트를 ReconciliationClassifier 규칙으로 나눠 센다.
 *
 * @param unpricedEventCount                실제 사용량은 있으나 가격을 몰라 금액이 비어 있는 이벤트 수 (UNPRICED)
 * @param reconciliationRequiredEventCount  격리되었거나 사용량을 몰라 사람이 대조해야 하는 이벤트 수 (RECONCILIATION_REQUIRED)
 */
public record ReconciliationSummaryResponse(
        String projectId,
        String environment,
        LocalDateTime from,
        LocalDateTime to,
        long unpricedEventCount,
        long reconciliationRequiredEventCount) {
}
