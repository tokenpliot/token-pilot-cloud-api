package com.tokenledgercloud.api.domain.budget.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "monthly_budget_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyBudgetSetting {

	@Id
	@Column(length = 36)
	private String id;

	@Column(name = "organization_id", nullable = false, length = 36)
	private String organizationId;

	@Column(name = "project_id", length = 36)
	private String projectId;

	@Column(length = 20)
	private String environment;

	@Column(name = "limit_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal limitUsd;

	@Column(nullable = false, length = 3)
	private String currency;
	@Column(name = "policy_version", length = 128)
	private String policyVersion;
	@Column(name = "updated_by_type", nullable = false, length = 30)
	private String updatedByType;
	@Column(name = "updated_by_id", length = 128)
	private String updatedById;

	@Column(name = "threshold_50_enabled", nullable = false)
	private Boolean threshold50Enabled;

	@Column(name = "threshold_80_enabled", nullable = false)
	private Boolean threshold80Enabled;

	@Column(name = "threshold_100_enabled", nullable = false)
	private Boolean threshold100Enabled;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		if (currency == null) currency = "USD";
		if (updatedByType == null) updatedByType = "UNKNOWN";
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}
		if (createdAt == null) createdAt = LocalDateTime.now();
		if (updatedAt == null) updatedAt = LocalDateTime.now();

		if (threshold50Enabled == null) threshold50Enabled = true;
		if (threshold80Enabled == null) threshold80Enabled = true;
		if (threshold100Enabled == null) threshold100Enabled = true;
	}

	@PreUpdate
	void preUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
