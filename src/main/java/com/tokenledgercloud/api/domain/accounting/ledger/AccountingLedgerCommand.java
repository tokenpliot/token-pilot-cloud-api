package com.tokenledgercloud.api.domain.accounting.ledger;

import java.time.LocalDateTime;
import java.util.Objects;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.TokenSemantics;

/**
 * 원장 writer에 넘기는 입력 값. 이벤트 한 건과, 그 이벤트에 기록할 금액(추정/실제) 한 개를 담는다.
 * Observe 수집 API(v2)의 ObserveIngestionService가 만든다.
 *
 * @param amount 기록할 금액. SUCCEEDED면 필수, USAGE_UNKNOWN이면 반드시 null이다.
 *               PROVIDER_FAILED와 BLOCKED_BY_CLIENT는 선택이다. (공급자 실패만으로 사용량 0이라고 확정하지 않는다)
 */
public record AccountingLedgerCommand(
		String organizationId,      // 조직 id
		String projectId,           // 프로젝트 id
		String apiKeyId,            // 요청에 쓴 API 키 id (없을 수 있음)
		String environment,         // 환경 (prod, dev 등)
		String requestId,           // 요청 id (없을 수 있음)
		String eventId,             // 멱등성 키. 같은 값을 여러 번 보내도 한 번만 반영된다.
		String payloadFingerprint,  // 본문 지문(SHA-256 hex 64자). 같은 eventId에 다른 본문이 오는지 판별한다.
		String schemaVersion,       // 이벤트가 따른 계약 버전 (예: observe-v1). 최대 50자.
		TokenSemantics tokenSemantics, // 토큰 항목의 포함 의미. LEGACY_UNKNOWN은 쓸 수 없다.
		String provider,            // LLM 공급자
		String model,               // 모델 이름
		long promptTokens,          // 프롬프트 토큰
		long completionTokens,      // 응답 토큰
		long reasoningTokens,       // 추론 토큰
		long cachedPromptTokens,    // 캐시된 프롬프트 토큰
		long totalTokens,           // 전체 토큰. 항목을 더해 추정하지 않고 호출자가 명시한다.
		LocalDateTime occurredAt,   // 호출 발생 시각
		CallStatus callStatus,      // 호출 결과
		AccountingAmount amount,    // 기록할 금액 (USAGE_UNKNOWN이면 null)
		String pricingSnapshotId,   // 가격 계산에 쓴 스냅샷 id (SERVER_CATALOG일 때 필수)
		String evidenceReference,   // 공급자 청구 등 근거 식별자 (PROVIDER_BILLING일 때 필수)
		String reasonCode,          // 기록 사유 코드
		String actorType,           // 기록 주체 종류
		String actorId,             // 기록 주체 식별자
		String metadataJson) {      // 금지 키를 뺀 metadata JSON (없으면 null). usage_events.metadata_json에 그대로 저장한다.

	// 레코드 생성 시점에 입력 값을 검사한다. 잘못된 값은 DB에 닿기 전에 막는다.
	public AccountingLedgerCommand {
		// 필수 문자열이 비어 있지 않은지 확인한다.
		requireText(organizationId, "organizationId");
		requireText(projectId, "projectId");
		requireText(environment, "environment");
		requireText(eventId, "eventId");
		requireText(provider, "provider");
		requireText(model, "model");
		requireText(reasonCode, "reasonCode");
		requireText(actorType, "actorType");
		requireText(actorId, "actorId");
		// 지문은 DB 컬럼(char(64))과 같은 길이여야 한다.
		if (payloadFingerprint == null || payloadFingerprint.length() != 64) {
			throw new IllegalArgumentException("payloadFingerprint must be 64 characters");
		}
		// 계약 버전은 DB 컬럼(varchar(50))에 맞는 비어 있지 않은 문자열이어야 한다.
		requireText(schemaVersion, "schemaVersion");
		if (schemaVersion.length() > 50) {
			throw new IllegalArgumentException("schemaVersion must be at most 50 characters");
		}
		// 토큰 의미는 필수이고, 기존 행 전용 값은 쓸 수 없다.
		Objects.requireNonNull(tokenSemantics, "tokenSemantics");
		if (tokenSemantics == TokenSemantics.LEGACY_UNKNOWN) {
			throw new IllegalArgumentException("LEGACY_UNKNOWN token semantics is reserved for pre-ledger rows");
		}
		// 시각과 호출 결과는 필수다.
		Objects.requireNonNull(occurredAt, "occurredAt");
		Objects.requireNonNull(callStatus, "callStatus");
		// 토큰 수는 음수일 수 없다.
		if (promptTokens < 0 || completionTokens < 0 || reasoningTokens < 0
				|| cachedPromptTokens < 0 || totalTokens < 0) {
			throw new IllegalArgumentException("token counts must not be negative");
		}
		// 기존 행 전용 상태는 새 수집에 쓸 수 없다.
		if (callStatus == CallStatus.LEGACY_UNKNOWN) {
			throw new IllegalArgumentException("LEGACY_UNKNOWN is reserved for pre-ledger rows");
		}
		// 사용량을 모르는 호출은 금액을 만들지 않는다. (0달러로 위장 금지)
		if (callStatus == CallStatus.USAGE_UNKNOWN && amount != null) {
			throw new IllegalArgumentException("USAGE_UNKNOWN must not carry an amount");
		}
		// 호출이 성공했다면 금액(PRICED 또는 UNPRICED)이 있어야 한다.
		// 공급자 실패와 클라이언트 차단은 금액 없이도 기록할 수 있다.
		if (callStatus == CallStatus.SUCCEEDED && amount == null) {
			throw new IllegalArgumentException("amount is required when callStatus is SUCCEEDED");
		}
	}

	// 문자열이 null이거나 공백뿐이면 예외를 던지는 도우미 메서드.
	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
