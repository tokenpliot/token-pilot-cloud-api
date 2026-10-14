package com.tokenledgercloud.api.domain.accounting.observe.dto;

import java.math.BigDecimal;

/**
 * 월 지출 요약. 기존(v0) 예산 응답은 바꾸지 않고, 원장을 반영한 숫자는 이 응답으로 따로 제공한다.
 *
 * @param legacySpentUsd          기존 방식으로 기록된 이벤트의 합계
 * @param ledgerConfirmedSpentUsd 새 원장에서 실제 사용량이고 가격이 확정된 금액의 합계
 * @param totalSpentUsd           위 두 값의 합계
 * @param unreflectedEventCount   지출에 반영되지 않은 이벤트 수. 0이 아니면 실제 지출이 totalSpentUsd보다 클 수 있다.
 */
public record LedgerSpendSummaryResponse(
        String projectId,
        String environment,
        String month,
        BigDecimal legacySpentUsd,
        BigDecimal ledgerConfirmedSpentUsd,
        BigDecimal totalSpentUsd,
        long unreflectedEventCount,
        String currency) {
}
