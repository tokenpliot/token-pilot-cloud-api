package com.tokenledgercloud.api.domain.ingestion.dto;

/**
 * Observe 이벤트 수집 응답.
 *
 * @param eventId      요청에 담겨 온 이벤트 id
 * @param usageEventId 서버에 저장된 usage_events 행의 id
 * @param duplicate    같은 이벤트가 이미 있어서 새로 쌓지 않았으면 true
 */
public record ObserveEventResponse(String eventId, String usageEventId, boolean duplicate) {
}
