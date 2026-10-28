package com.tokenledgercloud.api.domain.policy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.tokenledgercloud.api.global.util.HashingUtil;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "policy_snapshots", uniqueConstraints = {
	@UniqueConstraint(name = "uk_policy_snapshot_org_version", columnNames = {"organization_id", "version"}),
	@UniqueConstraint(name = "uk_policy_snapshot_revision", columnNames = {"policy_id", "revision"})
})
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicySnapshot {
	@Id @Column(length = 36) private String id;
	@Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
	@Column(name = "project_id", length = 36) private String projectId;
	@Column(length = 20) private String environment;
	@Column(nullable = false, length = 128) private String version;
	@Column(name = "policy_id", length = 36) private String policyId;
	private Long revision;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "snapshot_json", nullable = false, columnDefinition = "json") private String snapshotJson;
	@Column(nullable = false, length = 64) private String checksum;
	@Column(name = "actor_type", nullable = false, length = 30) private String actorType;
	@Column(name = "actor_id", nullable = false, length = 128) private String actorId;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
	@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "change_type", length = 30) private PolicyChangeType changeType;
	@Column(length = 500) private String reason;
	@Column(name = "restored_from_version") private Long restoredFromVersion;

	static PolicySnapshot capture(CustomerPolicy policy, PolicyRules rules, String memberId,
		PolicyChangeType changeType, String reason, Long restoredFromVersion, String legacyBudgetId) {
		PolicySnapshot s = new PolicySnapshot();
		s.id = UUID.randomUUID().toString();
		s.organizationId = policy.getOrganizationId();
		s.projectId = policy.getProjectId();
		s.environment = policy.environment();
		s.policyId = policy.getId();
		s.revision = policy.getCurrentVersion();
		// V6 requires version to be unique across an organization, not only within a policy.
		s.version = s.policyId + ":" + s.revision;
		s.actorType = "MEMBER";
		s.actorId = memberId;
		s.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		s.changeType = changeType;
		s.reason = reason;
		s.restoredFromVersion = restoredFromVersion;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", "policy-v1");
		payload.put("policyId", s.policyId);
		payload.put("version", s.version);
		payload.put("organizationId", s.organizationId);
		payload.put("projectId", s.projectId);
		payload.put("environment", s.environment);
		payload.put("monthlyWindow", "UTC_CALENDAR_MONTH");
		payload.put("composition", "ALL_APPLICABLE_SCOPES");
		payload.put("enforcement", "NO_AUTOMATIC_ENFORCEMENT");
		payload.put("rules", rules);
		payload.put("legacyBudgetId", legacyBudgetId);
		s.snapshotJson = PolicyJson.write(payload);
		s.checksum = HashingUtil.sha256(s.snapshotJson);
		return s;
	}

	public PolicyRules rules() { return PolicyJson.rules(snapshotJson); }
}
