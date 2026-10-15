package com.tokenledgercloud.api.domain.ingestion.dto;

import java.time.LocalDateTime;

import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;

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
 * @param occurredAt     호출이 발생한 시각(UTC)
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
	@NotNull LocalDateTime occurredAt
) {
}
