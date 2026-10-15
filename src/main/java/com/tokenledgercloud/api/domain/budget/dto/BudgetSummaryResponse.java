package com.tokenledgercloud.api.domain.budget.dto;

import java.math.BigDecimal;
import java.util.List;

public record BudgetSummaryResponse(
	String projectId,
	String environment,
	String month,
	BigDecimal budgetLimitUsd,
	BigDecimal spentUsd,
	BigDecimal remainingUsd,
	BigDecimal usagePercent,
	List<Integer> thresholds
) {
}