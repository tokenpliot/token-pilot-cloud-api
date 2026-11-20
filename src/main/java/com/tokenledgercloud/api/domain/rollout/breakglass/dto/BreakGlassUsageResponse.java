package com.tokenledgercloud.api.domain.rollout.breakglass.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import com.tokenledgercloud.api.domain.rollout.breakglass.BreakGlassStatus;

/**
 * break-glass 하나로 우회한 호출의 사용량·비용. (이슈 #19)
 *
 * @param windowStart  유효 기간 시작(발급 시각, UTC)
 * @param windowEnd    유효 기간 끝(취소 시각, 없으면 만료 시각, UTC). 이 시각은 기간에 들지 않는다.
 * @param withinWindow 유효 기간 안에 일어난 우회 호출
 * @param outsideWindow 같은 grant id가 붙었지만 유효 기간 밖에 일어난 호출. 0이 아니면 SDK가 만료·취소를 늦게 반영했다는 뜻이다
 */
public record BreakGlassUsageResponse(
		String grantId,
		String projectId,
		String environment,
		BreakGlassStatus status,
		OffsetDateTime windowStart,
		OffsetDateTime windowEnd,
		Summary withinWindow,
		Summary outsideWindow,
		OffsetDateTime queriedAt
) {

	/**
	 * @param events            이벤트 수
	 * @param totalTokens       토큰 합
	 * @param actualCostUsd     최신 ACTUAL 개정본이 PRICED인 금액의 합 (#11 원장과 같은 기준)
	 * @param unpricedEvents    ACTUAL 값이 없거나 가격 미상인 이벤트 수. 이 비용은 actualCostUsd에 들어 있지 않다
	 * @param legacyEvents      기존 수집 경로(LEGACY_UNVERIFIED)로 들어온 이벤트 수
	 * @param legacyCostUsd     기존 수집 경로 이벤트의 usage_events.total_cost_usd 합. 검증되지 않은 값이다
	 * @param quarantinedEvents 귀속이 불확실해 격리된 이벤트 수. 비용 합계에서 뺀다
	 * @param callStatusCounts  call_status별 이벤트 수
	 */
	public record Summary(
			long events,
			long totalTokens,
			BigDecimal actualCostUsd,
			long unpricedEvents,
			long legacyEvents,
			BigDecimal legacyCostUsd,
			long quarantinedEvents,
			Map<String, Long> callStatusCounts
	) {
	}
}
