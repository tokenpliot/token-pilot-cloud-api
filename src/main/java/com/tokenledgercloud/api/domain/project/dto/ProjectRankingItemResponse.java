package com.tokenledgercloud.api.domain.project.dto;

import java.math.BigDecimal;

public record ProjectRankingItemResponse(
	String projectId,
	String projectName,
	String environment,
	String topModel,
	BigDecimal costUsd,
	int budgetUsagePercent,
	int rank
) {
}
