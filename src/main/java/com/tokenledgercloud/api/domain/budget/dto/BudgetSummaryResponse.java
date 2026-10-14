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
	List<Integer> thresholds,
	// [이슈 #11] 아직 정산되지 않아 spentUsd에 반영되지 않은 이벤트 수. 0이 아니면 실제 지출이 spentUsd보다 클 수 있다.
	long unsettledEventCount
) {
}