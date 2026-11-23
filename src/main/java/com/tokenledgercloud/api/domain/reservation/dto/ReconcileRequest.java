package com.tokenledgercloud.api.domain.reservation.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.tokenledgercloud.api.domain.reservation.SettlementAction;

/**
 * 운영자 수동 정산 요청 본문.
 *
 * @param action       COMMIT(청구서 등으로 확인한 실제 비용으로 확정) 또는 WRITE_OFF(끝내 확인하지 못해 예약 금액으로 확정)
 * @param totalCostUsd COMMIT일 때 필수, WRITE_OFF일 때는 비워야 한다
 * @param occurredAt   정산 사건 시각. 재시도 때 같은 값을 보내야 같은 정산으로 본다
 * @param reason       왜 수동으로 정산하는지 (필수, 500자 이하). 감사 로그에 남는다
 */
public record ReconcileRequest(SettlementAction action, BigDecimal totalCostUsd, Instant occurredAt, String reason) {
}
