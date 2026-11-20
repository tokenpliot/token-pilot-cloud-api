package com.tokenledgercloud.api.domain.decision;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * 판정 요청 본문의 지문(SHA-256, 소문자 hex 64자). 같은 Idempotency-Key에 같은 본문이 왔는지 판별한다.
 * Idempotency-Key 헤더와 API Key는 넣지 않는다. 각 필드를 길이 접두로 인코딩해 구분자가 든 값끼리 충돌하지 않는다.
 * metadata는 키를 정렬해 넣으므로 순서가 달라도 같은 지문이다. 금액은 소수 표기만 다른 값(0.01, 0.010)을 같게 본다.
 */
final class DecisionFingerprint {

	// 규칙이 바뀌면 올려서 옛 지문과 섞이지 않게 한다.
	private static final String VERSION = "decision-fp-v1";

	private DecisionFingerprint() {
	}

	static String of(DecisionCommand command) {
		StringBuilder canonical = new StringBuilder(VERSION).append('\n');
		field(canonical, "schemaVersion", command.schemaVersion());
		field(canonical, "requestId", command.requestId());
		field(canonical, "environment", command.environment());
		field(canonical, "provider", command.provider());
		field(canonical, "model", command.model());
		field(canonical, "clientMode", command.clientMode().name());
		field(canonical, "estimatedInputTokens", Long.toString(command.estimatedInputTokens()));
		field(canonical, "maxOutputTokens", Long.toString(command.maxOutputTokens()));
		field(canonical, "estimatedCostUsd", amount(command.estimatedCostUsd()));
		field(canonical, "pricingVersion", command.pricingVersion());
		for (Map.Entry<String, String> entry : new TreeMap<>(command.metadata()).entrySet()) {
			field(canonical, "metadata." + entry.getKey(), entry.getValue());
		}
		return HashingUtil.sha256(canonical.toString());
	}

	private static String amount(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	// 없는 값(null)과 빈 문자열을 구분한다.
	private static void field(StringBuilder canonical, String name, String value) {
		canonical.append(name.length()).append(':').append(name).append('=');
		if (value == null) {
			canonical.append('-');
		} else {
			canonical.append(value.length()).append(':').append(value);
		}
		canonical.append('\n');
	}
}
