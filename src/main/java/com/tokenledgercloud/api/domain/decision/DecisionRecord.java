package com.tokenledgercloud.api.domain.decision;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판정 한 건의 불변 기록. 멱등 키, 요청 지문, 정책·가격 snapshot 묶음, 재현용 입력과 처음 응답한 내용을 담는다.
 * 한 번 저장하면 수정하거나 지우지 않는다.
 */
@Entity
@Table(name = "decision_records", uniqueConstraints = {
	@UniqueConstraint(name = "uk_decision_idempotency", columnNames = {"project_id", "idempotency_key"}),
	@UniqueConstraint(name = "uk_decision_context", columnNames = {"policy_request_context_id"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DecisionRecord {

	@Id @Column(length = 36) private String id;
	@Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
	@Column(name = "project_id", nullable = false, length = 36) private String projectId;
	@Column(name = "api_key_id", nullable = false, length = 36) private String apiKeyId;
	@Column(name = "policy_request_context_id", nullable = false, length = 36) private String policyRequestContextId;
	@Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
	@Column(name = "request_fingerprint", nullable = false, length = 64) private String requestFingerprint;
	@Enumerated(EnumType.STRING) @Column(name = "client_mode", nullable = false, length = 10) private EnforcementMode clientMode;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DecisionOutcome outcome;
	@Column(name = "algorithm_version", nullable = false, length = 30) private String algorithmVersion;
	@Column(name = "evaluated_at", nullable = false) private LocalDateTime evaluatedAt;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evaluation_input_json", nullable = false, columnDefinition = "json") private String evaluationInputJson;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_json", nullable = false, columnDefinition = "json") private String responseJson;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

	/** decisionId로 응답에 내보내는 값. */
	public String decisionId() {
		return decisionIdOf(id);
	}

	public static String newId() {
		return UUID.randomUUID().toString();
	}

	public static String decisionIdOf(String id) {
		return "dec-" + id;
	}

	/** @param id {@link #newId()}로 만든 값. 응답의 decisionId를 저장 전에 알 수 있게 호출자가 만든다. */
	public static DecisionRecord of(String id, String organizationId, String projectId, String apiKeyId, String policyRequestContextId,
		String idempotencyKey, String requestFingerprint, EnforcementMode clientMode, DecisionOutcome outcome,
		String algorithmVersion, LocalDateTime evaluatedAtUtc, String evaluationInputJson, String responseJson) {
		DecisionRecord record = new DecisionRecord();
		record.id = id;
		record.organizationId = organizationId;
		record.projectId = projectId;
		record.apiKeyId = apiKeyId;
		record.policyRequestContextId = policyRequestContextId;
		record.idempotencyKey = idempotencyKey;
		record.requestFingerprint = requestFingerprint;
		record.clientMode = clientMode;
		record.outcome = outcome;
		record.algorithmVersion = algorithmVersion;
		record.evaluatedAt = evaluatedAtUtc.truncatedTo(ChronoUnit.MICROS);
		record.evaluationInputJson = evaluationInputJson;
		record.responseJson = responseJson;
		record.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		return record;
	}
}
