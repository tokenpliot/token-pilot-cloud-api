package com.tokenledgercloud.api.domain.decision;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 판정 요청 본문(control-plane-v1.yaml의 DecisionRequest)을 검증한 뒤의 값. HTTP 검증은 컨트롤러 쪽이 맡는다.
 * 자유 텍스트 필드가 없다. 원문 prompt는 이 타입에 담길 자리가 없다.
 *
 * @param estimatedCostUsd SDK의 추정 비용. 지문에만 쓰고 판정에도, 가격이 없을 때 대신 쓰는 값으로도 쓰지 않는다
 * @param pricingVersion   SDK가 쓴 가격 버전. 지문에만 쓴다
 * @param metadata         가명화된 문자열 키·값. 지문에만 쓰고 저장하지 않는다
 */
public record DecisionCommand(
	String schemaVersion,
	String requestId,
	String environment,
	String provider,
	String model,
	EnforcementMode clientMode,
	long estimatedInputTokens,
	long maxOutputTokens,
	BigDecimal estimatedCostUsd,
	String pricingVersion,
	Map<String, String> metadata
) {
	public DecisionCommand {
		Objects.requireNonNull(schemaVersion, "schemaVersion");
		Objects.requireNonNull(requestId, "requestId");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(clientMode, "clientMode");
		if (estimatedInputTokens < 0 || maxOutputTokens < 0) {
			throw new IllegalArgumentException("Token counts must be nonnegative");
		}
		metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
	}
}
