package com.tokenledgercloud.api.domain.policy;

import java.util.UUID;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_policies", uniqueConstraints = @UniqueConstraint(
	name = "uk_customer_policy_scope", columnNames = {"organization_id", "project_id", "environment_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerPolicy {
	@Id @Column(length = 36)
	private String id;
	@Column(name = "organization_id", nullable = false, length = 36, updatable = false)
	private String organizationId;
	@Column(name = "project_id", nullable = false, length = 36, updatable = false)
	private String projectId;
	/** Empty string is the project-wide scope; API environments themselves may not be blank. */
	@Column(name = "environment_key", nullable = false, length = 20, updatable = false)
	private String environmentKey;
	@Column(name = "current_version", nullable = false)
	private long currentVersion;

	static CustomerPolicy create(String organizationId, String projectId, String environment) {
		CustomerPolicy policy = new CustomerPolicy();
		policy.id = UUID.randomUUID().toString();
		policy.organizationId = organizationId;
		policy.projectId = projectId;
		policy.environmentKey = environment == null ? "" : environment;
		return policy;
	}

	void advance() { currentVersion = Math.incrementExact(currentVersion); }
	public String environment() { return environmentKey.isEmpty() ? null : environmentKey; }
}
