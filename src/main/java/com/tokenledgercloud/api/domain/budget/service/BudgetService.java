package com.tokenledgercloud.api.domain.budget.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.budget.dto.BudgetSummaryResponse;
import com.tokenledgercloud.api.domain.budget.dto.BudgetUpsertRequest;
import com.tokenledgercloud.api.domain.budget.dto.BudgetUpsertResponse;
import com.tokenledgercloud.api.domain.budget.entity.MonthlyBudgetSetting;
import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.BudgetNotFoundException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.exception.InvalidMonthException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BudgetService {

	private static final String DEFAULT_ORGANIZATION_ID = "default-org";

	private final MonthlyBudgetSettingRepository budgetRepository;
	private final UsageLogRepository usageLogRepository;
	private final BudgetCalculator budgetCalculator;

	@Transactional
	public BudgetUpsertResponse upsert(BudgetUpsertRequest request) {
		if (!"MONTHLY".equals(request.periodType())) {
			throw new ApiException(ErrorCode.INVALID_PERIOD_TYPE);
		}

		MonthlyBudgetSetting budget = findBudget(request.projectId(), request.environment())
			.orElseGet(() -> MonthlyBudgetSetting.builder()
				.organizationId(DEFAULT_ORGANIZATION_ID)
				.projectId(request.projectId())
				.environment(request.environment())
				.build()
			);

		budget.setLimitUsd(request.limitUsd());
		budgetCalculator.applyThresholds(budget, request.thresholds());

		MonthlyBudgetSetting saved = budgetRepository.save(budget);

		return new BudgetUpsertResponse(
			saved.getId(),
			saved.getProjectId(),
			saved.getEnvironment(),
			saved.getLimitUsd(),
			budgetCalculator.toThresholds(saved)
		);
	}

	@Transactional(readOnly = true)
	public BudgetSummaryResponse getSummary(String projectId, String environment, String month) {
		YearMonth yearMonth = parseMonth(month);

		MonthlyBudgetSetting budget = findBudget(projectId, environment)
			.orElseThrow(BudgetNotFoundException::new);

		LocalDate startDate = yearMonth.atDay(1);
		LocalDate endDate = yearMonth.plusMonths(1).atDay(1);

		LocalDateTime from = startDate.atStartOfDay();
		LocalDateTime to = endDate.atStartOfDay();

		BigDecimal spentUsd = usageLogRepository.sumTotalCostUsd(projectId, environment, from, to);

		var calculation = budgetCalculator.calculate(
			budget.getLimitUsd(),
			spentUsd
		);

		return new BudgetSummaryResponse(
			projectId,
			environment,
			month,
			calculation.limitUsd(),
			calculation.spentUsd(),
			calculation.remainingUsd(),
			calculation.usagePercent(),
			budgetCalculator.toThresholds(budget)
		);
	}

	private java.util.Optional<MonthlyBudgetSetting> findBudget(String projectId, String environment) {
		if (projectId == null && environment == null) {
			return budgetRepository.findFirstByOrganizationIdAndProjectIdIsNullAndEnvironmentIsNull(
				DEFAULT_ORGANIZATION_ID
			);
		}

		return budgetRepository.findFirstByOrganizationIdAndProjectIdAndEnvironment(
			DEFAULT_ORGANIZATION_ID,
			projectId,
			environment
		);
	}

	private YearMonth parseMonth(String month) {
		try {
			return YearMonth.parse(month);
		} catch (Exception e) {
			throw new InvalidMonthException();
		}
	}
}