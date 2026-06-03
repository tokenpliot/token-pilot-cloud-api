package com.tokenledgercloud.api.domain.project.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProjectDetailResponse(
	String projectId,
	String name,
	String projectKey,
	String status,
	List<String> environments,
	String defaultModel,
	BigDecimal latestMonthlyCostUsd,
	Long latestMonthlyTokens
) {
}
