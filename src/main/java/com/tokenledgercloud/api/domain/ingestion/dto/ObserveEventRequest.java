package com.tokenledgercloud.api.domain.ingestion.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;
import com.tokenledgercloud.api.domain.ingestion.validation.ValidIngestionMetadata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Observe 사용 이벤트 수집 요청(v2). 토큰은 "상세가 부모에 포함되는" 형태(INCLUSIVE_V1)여야 한다.
 * 즉 promptTokens는 캐시 토큰을 포함하고 completionTokens는 추론 토큰을 포함한다.
 * 금액은 받지 않는다. 비용은 서버가 가격표로 계산한다.
 *
 * @param promptTokens   호출이 성공(SUCCEEDED)했을 때 필수
 * @param completionTokens 호출이 성공(SUCCEEDED)했을 때 필수
 * @param cachedPromptTokens 모르면 생략한다. 0과 다르다.
 * @param reasoningTokens    모르면 생략한다. 0과 다르다.
 * @param occurredAt     호출이 발생한 시각. 오프셋이 필수이며(v1과 같은 형식, 예: 2026-10-01T00:00:00Z) 서버가 UTC로 바꿔 저장한다.
 * @param metadata       선택. 추적 상관관계(trace_id, app_request_id 등). v1 수집과 규칙이 같다.
 *                       기본 모드는 금지 키(prompt, completion, messages 등)를 저장 전에 버리고 형식 위반은 경고만 남기며,
 *                       엄격 모드(token-pilot.ingestion.strict-metadata=true)는 둘 다 400으로 거절한다.
 */
public record ObserveEventRequest(
	@NotBlank @Size(max = 128) String projectKey,
	@NotBlank @Size(max = 50) String environment,
	@NotBlank @Size(max = 128) String eventId,
	@Size(max = 128) String requestId,
	@NotBlank @Pattern(regexp = "observe-v1", message = "schemaVersion must be observe-v1") String schemaVersion,
	@NotNull TokenSemantics tokenSemantics,
	@NotBlank @Size(max = 128) String provider,
	@NotBlank @Size(max = 128) String model,
	@NotNull CallStatus callStatus,
	@PositiveOrZero Long promptTokens,
	@PositiveOrZero Long completionTokens,
	@PositiveOrZero Long cachedPromptTokens,
	@PositiveOrZero Long reasoningTokens,
	@NotNull OffsetDateTime occurredAt,
	@ValidIngestionMetadata Map<String, Object> metadata
) {
}
