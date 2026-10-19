package com.tokenledgercloud.api.domain.accounting.observe.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 가격 누락 조회 응답. 실제 사용량은 있는데 가격을 찾지 못해 금액이 비어 있는 이벤트를 모델별로 보여준다.
 * 금액은 0이 아니라 "모름"이므로 이 응답에는 금액 필드를 두지 않는다.
 *
 * @param unpricedEventCount 가격 누락 이벤트 총 수
 * @param items              공급자/모델별 내역
 */
public record UnpricedSummaryResponse(
		String projectId,
		String environment,
		LocalDateTime from,
		LocalDateTime to,
		long unpricedEventCount,
		List<Item> items) {

	/** 공급자/모델별 한 줄. */
	public record Item(String provider, String model, long eventCount, long totalTokens) {
	}
}
