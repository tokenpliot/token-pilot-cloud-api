package com.tokenledgercloud.api.domain.accounting.ledger;

/**
 * 원장 기록 결과.
 *
 * @param usageEventId 저장된(또는 이미 저장되어 있던) usage_events 행의 id
 * @param duplicate    같은 이벤트가 이미 있어서 새로 쌓지 않았으면 true
 */
public record AccountingLedgerResult(String usageEventId, boolean duplicate) {
}
