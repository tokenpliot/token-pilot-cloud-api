package com.tokenledgercloud.api.domain.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;

/**
 * Observe 이벤트 본문의 지문(SHA-256, 소문자 hex 64자). 같은 eventId에 다른 내용이 오는지 판별하는 데 쓴다.
 * 가격 계산 결과는 포함하지 않는다. 가격표가 바뀐 뒤 같은 이벤트를 재전송해도 중복으로 인식되어야 하기 때문이다.
 */
final class ObserveEventFingerprint {

	// 지문 계산 규칙의 이름. 규칙이 바뀌면 올려서 옛 지문과 섞이지 않게 한다.
	private static final String VERSION = "observe-fp-v1";

	// 유틸리티 클래스이므로 인스턴스 생성을 막는다.
	private ObserveEventFingerprint() {
	}

	/** 인증된 프로젝트와 요청 필드를 정해진 순서로 이어 붙여 지문을 만든다. */
	static String of(AuthenticatedProjectApiKey auth, ObserveEventRequest request) {
		StringBuilder canonical = new StringBuilder(VERSION).append('\n');
		// 필드 순서를 고정하고, 없는 값은 "null"로 적어 0과 구별한다.
		field(canonical, "projectId", auth.projectId());
		field(canonical, "environment", auth.environment());
		field(canonical, "eventId", request.eventId());
		field(canonical, "requestId", request.requestId());
		field(canonical, "schemaVersion", request.schemaVersion());
		field(canonical, "tokenSemantics", request.tokenSemantics());
		field(canonical, "provider", request.provider());
		field(canonical, "model", request.model());
		field(canonical, "callStatus", request.callStatus());
		field(canonical, "promptTokens", request.promptTokens());
		field(canonical, "completionTokens", request.completionTokens());
		field(canonical, "cachedPromptTokens", request.cachedPromptTokens());
		field(canonical, "reasoningTokens", request.reasoningTokens());
		field(canonical, "occurredAt", request.occurredAt());
		return sha256Hex(canonical.toString());
	}

	// "이름=값" 한 줄을 덧붙인다. 값이 null이면 "null"로 적는다.
	private static void field(StringBuilder out, String name, Object value) {
		out.append(name).append('=').append(value == null ? "null" : value).append('\n');
	}

	// 문자열의 SHA-256을 소문자 hex로 계산한다.
	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			// SHA-256은 모든 자바 구현이 지원하므로 여기에 올 일이 없다.
			throw new IllegalStateException(exception);
		}
	}
}
