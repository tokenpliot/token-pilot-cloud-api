package com.tokenledgercloud.api.domain.policy;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read mapping of the existing V1 membership table; policy management does not grant memberships. */
@Entity
@Table(name = "organization_members", uniqueConstraints = @UniqueConstraint(
	name = "uk_organization_members_org_user", columnNames = {"organization_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationMembership {
	@Id @Column(length = 36) private String id;
	@Column(name = "organization_id", nullable = false, length = 36) private String organizationId;
	@Column(name = "user_id", nullable = false, length = 36) private String userId;
	@Column(nullable = false, length = 30) private String role;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
	@Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
