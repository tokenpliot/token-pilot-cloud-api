package com.tokenledgercloud.api.domain.accounting.observe.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 미귀속 비용 조회 응답. 귀속(프로젝트/환경)이 불확실해 격리된(QUARANTINED) 이벤트를 다룬다.
 *
 * @param quarantinedEventCount 격리된 이벤트 수
 * @param pricedActualAmountUsd 그 이벤트들의 최신 실제 확정 금액 합계. 가격 미상인 이벤트는 더하지 않는다.
 * @param currency              통화 (현재 USD만 사용)
 */
public record UnattributedCostResponse(
		String projectId,
		String environment,
		LocalDateTime from,
		LocalDateTime to,
		long quarantinedEventCount,
		BigDecimal pricedActualAmountUsd,
		String currency) {
}
