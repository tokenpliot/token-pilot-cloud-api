package com.tokenledgercloud.api.domain.budget.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.tokenledgercloud.api.domain.budget.entity.MonthlyBudgetSetting;

public interface MonthlyBudgetSettingRepository extends JpaRepository<MonthlyBudgetSetting, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<MonthlyBudgetSetting> findAllByOrganizationIdAndProjectIdAndEnvironment(String organizationId, String projectId, String environment);

	Optional<MonthlyBudgetSetting> findFirstByOrganizationIdAndProjectIdAndEnvironment(
		String organizationId,
		String projectId,
		String environment
	);

	Optional<MonthlyBudgetSetting> findFirstByOrganizationIdAndProjectIdIsNullAndEnvironmentIsNull(
		String organizationId
	);
}
