package com.tokenledgercloud.api.domain.budget.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.observe.LedgerSpendRepository;
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
	// [이슈 #11] 새 원장(usage_accounting_values)의 확정 지출과 미정산 건수를 읽는 저장소.
	private final LedgerSpendRepository ledgerSpendRepository;

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

		// [이슈 #11] 지출 = 기존 행의 합계 + 새 원장의 확정(ACTUAL, PRICED) 금액 합계. 두 집합은 겹치지 않는다.
		BigDecimal legacySpentUsd = usageLogRepository.sumTotalCostUsd(projectId, environment, from, to);
		BigDecimal ledgerSpentUsd = ledgerSpendRepository.sumConfirmedSpend(
			DEFAULT_ORGANIZATION_ID, projectId, environment, from, to,
			AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED
		);
		BigDecimal spentUsd = nullToZero(legacySpentUsd).add(nullToZero(ledgerSpentUsd));
		// [이슈 #11] 가격 미상이나 격리 등으로 아직 지출에 못 넣은 이벤트 수. 0원으로 숨기지 않고 따로 알려준다.
		long unsettledEventCount = ledgerSpendRepository.countUnsettled(
			DEFAULT_ORGANIZATION_ID, projectId, environment, from, to
		);

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
			budgetCalculator.toThresholds(budget),
			unsettledEventCount
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

	// [이슈 #11] 합계 쿼리 결과가 비어 있으면(null) 0으로 바꾼다.
	private BigDecimal nullToZero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private YearMonth parseMonth(String month) {
		try {
			return YearMonth.parse(month);
		} catch (Exception e) {
			throw new InvalidMonthException();
		}
	}
}