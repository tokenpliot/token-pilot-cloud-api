package com.tokenledgercloud.api.domain.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Observe 수집 API(v2, {@code POST /api/v2/observe/events}) 설정.
 * 엔드포인트 자체는 {@code token-pilot.observe.ingestion.enabled=true}일 때만 등록된다.
 *
 * @param rateUnitTokens pricing_plans 요율이 몇 토큰당 가격인지. 0이면 아직 확인 전이라 모든 이벤트를 가격 미상(UNPRICED)으로 기록한다.
 *                       한 번 가격을 매긴 뒤 이 값을 바꾸면 같은 가격표 버전의 스냅샷과 충돌하므로, 가격표 버전을 새로 올려야 다시 가격이 매겨진다.
 */
@ConfigurationProperties(prefix = "token-pilot.observe.ingestion")
public record ObserveIngestionProperties(
	@DefaultValue("0") long rateUnitTokens
) {
}
