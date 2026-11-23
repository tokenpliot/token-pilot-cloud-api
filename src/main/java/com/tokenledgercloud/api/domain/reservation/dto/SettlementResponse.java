package com.tokenledgercloud.api.domain.reservation.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;

/**
 * 정산 응답 (control-plane-v1.yaml의 SettlementResult). 금액은 소수점 6자리 문자열이고, 해당 없으면 필드를 뺀다.
 *
 * @param requestId 요청 본문의 requestId를 그대로 돌려준다. (SDK는 다른 값이 오면 응답을 INDETERMINATE로 버린다)
 * @param duplicate 같은 Idempotency-Key·같은 본문의 재전송이라 처음 결과를 돌려준 경우 true
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettlementResponse(
		String schemaVersion,
		String reservationId,
		String requestId,
		ReservationState previousState,
		ReservationState state,
		String committedCostUsd,
		String releasedCostUsd,
		boolean duplicate) {

	public static SettlementResponse from(SettlementResult result, String requestId) {
		return new SettlementResponse(SettlementRequest.SCHEMA_VERSION, result.reservationId(), requestId,
				result.previousState(), result.state(), usd(result.committedCostUsd()), usd(result.releasedCostUsd()),
				result.duplicate());
	}

	private static String usd(BigDecimal value) {
		return value == null ? null : value.setScale(6).toPlainString();
	}
}
