package com.tokenledgercloud.api.domain.ingestion.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.tokenledgercloud.api.domain.ingestion.dto.ObserveEventRequest;
import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * Observe 이벤트 본문의 지문(SHA-256, 소문자 hex 64자). 같은 eventId에 다른 내용이 오는지 판별하는 데 쓴다.
 * 가격 계산 결과는 포함하지 않는다. 가격표가 바뀐 뒤 같은 이벤트를 재전송해도 중복으로 인식되어야 하기 때문이다.
 * 필드 인코딩은 v1 지문({@link PayloadFingerprint})과 같은 길이 접두 방식이라 구분자가 든 값끼리 충돌하지 않는다.
 */
final class ObserveEventFingerprint {

	// 지문 계산 규칙의 이름. 규칙이 바뀌면 올려서 옛 지문과 섞이지 않게 한다.
	private static final String VERSION = "observe-fp-v1";

	// 유틸리티 클래스이므로 인스턴스 생성을 막는다.
	private ObserveEventFingerprint() {
	}

	/**
	 * 요청 필드를 정해진 순서로 이어 붙여 지문을 만든다.
	 *
	 * @param occurredAtUtc 요청의 발생 시각을 UTC로 바꾼 값. 같은 순간을 다른 오프셋으로 보내도 같은 지문이 된다.
	 * @param metadataJson  금지 키를 뺀 저장용 metadata JSON({@link IngestionMetadataSerializer#toJson}). 없으면 null.
	 */
	static String of(ObserveEventRequest request, LocalDateTime occurredAtUtc, String metadataJson) {
		StringBuilder canonical = new StringBuilder(VERSION).append('\n');
		PayloadFingerprint.field(canonical, "requestId", request.requestId());
		PayloadFingerprint.field(canonical, "schemaVersion", request.schemaVersion());
		PayloadFingerprint.field(canonical, "tokenSemantics", request.tokenSemantics());
		PayloadFingerprint.field(canonical, "provider", request.provider());
		PayloadFingerprint.field(canonical, "model", request.model());
		PayloadFingerprint.field(canonical, "callStatus", request.callStatus());
		PayloadFingerprint.field(canonical, "promptTokens", request.promptTokens());
		PayloadFingerprint.field(canonical, "completionTokens", request.completionTokens());
		PayloadFingerprint.field(canonical, "cachedPromptTokens", request.cachedPromptTokens());
		PayloadFingerprint.field(canonical, "reasoningTokens", request.reasoningTokens());
		// 저장 정밀도(마이크로초)에 맞춰 자른다.
		PayloadFingerprint.field(canonical, "occurredAt", occurredAtUtc.truncatedTo(ChronoUnit.MICROS));
		// metadata는 v1과 같이 금지 키를 뺀 저장용 JSON을 키 정렬해 넣는다. (키 순서가 달라도 같은 지문)
		// 비어 있으면 줄 자체를 넣지 않는다. metadata가 생기기 전에 저장된 행의 지문과 같게 유지해서,
		// 그 이벤트를 metadata 없이 재전송해도 계속 중복(200)으로 인식되게 한다.
		String metadata = PayloadFingerprint.canonicalMetadata(metadataJson);
		if (!metadata.isEmpty()) {
			PayloadFingerprint.field(canonical, "metadata", metadata);
		}
		return HashingUtil.sha256(canonical.toString());
	}
}
