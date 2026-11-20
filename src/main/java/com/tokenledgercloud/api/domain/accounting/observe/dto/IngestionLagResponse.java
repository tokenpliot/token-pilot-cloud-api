package com.tokenledgercloud.api.domain.accounting.observe.dto;

import java.time.LocalDateTime;

/**
 * 수집 지연 조회 응답. 시각은 모두 UTC다.
 *
 * @param eventCount                기간 안의 원장 이벤트 수
 * @param unsettledCount            그 중 아직 정산되지 않은 이벤트 수
 * @param lastReceivedAt            서버가 마지막으로 받은 시각, UTC (이벤트가 없으면 null)
 * @param lastOccurredAt            가장 최근 호출 발생 시각, UTC (이벤트가 없으면 null)
 * @param secondsSinceLastReceived  마지막 수신 후 지난 초. 클수록 수집이 멈춰 있다는 뜻 (이벤트가 없으면 null)
 * @param maxDelaySeconds           호출 발생부터 서버 수신까지 걸린 시간 중 가장 긴 것(초) (이벤트가 없으면 null)
 * @param averageDelaySeconds       호출 발생부터 서버 수신까지 걸린 시간의 평균(초, 소수점 버림) (이벤트가 없으면 null)
 */
public record IngestionLagResponse(
		String projectId,
		String environment,
		LocalDateTime from,
		LocalDateTime to,
		long eventCount,
		long unsettledCount,
		LocalDateTime lastReceivedAt,
		LocalDateTime lastOccurredAt,
		Long secondsSinceLastReceived,
		Long maxDelaySeconds,
		Long averageDelaySeconds) {
}
