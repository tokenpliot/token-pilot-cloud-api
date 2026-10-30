package com.tokenledgercloud.api.domain.policy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable references only; #15/#16 own decision inputs, outcomes and evaluation algorithm versions. */
@Entity
@Table(name = "policy_request_contexts", uniqueConstraints = @UniqueConstraint(
	name = "uk_policy_request_scope", columnNames = {"organization_id", "project_id", "environment", "request_id"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PolicyRequestContext {
	@Id @Column(length = 36) private String id;
	@Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
	@Column(name = "project_id", nullable = false, length = 36) private String projectId;
	@Column(nullable = false, length = 20) private String environment;
	@Column(name = "request_id", nullable = false, length = 100) private String requestId;
	@Column(name = "project_policy_snapshot_id", length = 36) private String projectPolicySnapshotId;
	@Column(name = "environment_policy_snapshot_id", length = 36) private String environmentPolicySnapshotId;
	@Column(name = "pricing_snapshot_id", length = 36) private String pricingSnapshotId;
	@Column(name = "actor_type", nullable = false, length = 30) private String actorType;
	@Column(name = "actor_id", nullable = false, length = 128) private String actorId;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

	static PolicyRequestContext capture(String org, String project, String environment, String requestId,
		PolicySnapshot projectPolicy, PolicySnapshot environmentPolicy, String priceId, String keyId) {
		PolicyRequestContext c = new PolicyRequestContext();
		c.id = UUID.randomUUID().toString();
		c.organizationId = org;
		c.projectId = project;
		c.environment = environment;
		c.requestId = requestId;
		// Inactive revisions are retained too, so the absence of an applicable rule is explainable.
		c.projectPolicySnapshotId = projectPolicy == null ? null : projectPolicy.getId();
		c.environmentPolicySnapshotId = environmentPolicy == null ? null : environmentPolicy.getId();
		c.pricingSnapshotId = priceId;
		c.actorType = "PROJECT_API_KEY";
		c.actorId = keyId;
		c.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		return c;
	}
}
