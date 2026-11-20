package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 프로젝트·환경의 Enforce를 만료 시간까지 우회하는 break-glass 권한. (V12 break_glass_grants)
 * 행 자체가 감사 기록이다: 누가·왜·언제 발급했고 누가·왜·언제 취소했는지를 지우지 않고 남긴다.
 * 시각은 모두 UTC로 저장한다.
 */
@Entity
@Table(name = "break_glass_grants",
		uniqueConstraints = @UniqueConstraint(name = "uk_break_glass_active_scope", columnNames = "active_scope_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BreakGlassGrant {

	/**
	 * 우회 호출의 사용량 이벤트 metadata에 grant id를 담는 키. SDK와 서버가 같은 이름을 써야 한다.
	 * ingestion metadata 키 규칙(^[a-z][a-z0-9_.-]{0,63}$)에 맞춘 이름이다.
	 */
	public static final String USAGE_METADATA_KEY = "break_glass_grant_id";

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "organization_id", nullable = false, length = 36)
	private String organizationId;

	@Column(name = "project_id", nullable = false, length = 36)
	private String projectId;

	@Column(nullable = false, length = 20)
	private String environment;

	@Column(nullable = false, length = 500)
	private String reason;

	@Column(name = "issued_by", nullable = false, length = 128)
	private String issuedBy;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Column(name = "revoked_by", length = 128)
	private String revokedBy;

	@Column(name = "revoke_reason", length = 500)
	private String revokeReason;

	// 유효한 동안만 값이 있고 UNIQUE라서, 같은 범위에 유효한 grant가 동시에 둘 생기지 않는다.
	@Column(name = "active_scope_key", length = 100)
	private String activeScopeKey;

	// 같은 grant를 동시에 취소·정리할 때 한쪽만 성공하게 한다.
	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void prePersist() {
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}
		if (createdAt == null) {
			createdAt = issuedAt;
		}
	}

	/** 범위를 하나의 문자열로 만든다. 조직 36 + 프로젝트 36 + 환경 20 + 구분자 2 = 최대 94자. */
	public static String scopeKey(String organizationId, String projectId, String environment) {
		return organizationId + ":" + projectId + ":" + environment;
	}

	/** 주어진 시각(UTC) 기준 상태. 취소가 만료보다 우선한다. */
	public BreakGlassStatus statusAt(LocalDateTime now) {
		if (revokedAt != null) {
			return BreakGlassStatus.REVOKED;
		}
		return now.isBefore(expiresAt) ? BreakGlassStatus.ACTIVE : BreakGlassStatus.EXPIRED;
	}

	/** 만료 전에 취소한다. 범위를 비워서 새 grant를 발급할 수 있게 한다. */
	void revoke(String actor, String reason, LocalDateTime now) {
		this.revokedAt = now;
		this.revokedBy = actor;
		this.revokeReason = reason;
		this.activeScopeKey = null;
	}

	/** 이미 만료된 grant가 차지하던 범위를 비운다. 만료 기록 자체는 그대로 남는다. */
	void releaseScope() {
		this.activeScopeKey = null;
	}
}
