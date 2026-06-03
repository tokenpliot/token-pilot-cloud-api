package com.tokenledgercloud.api.domain.usage.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ProjectUsageRankingProjection {

	String getProjectId();

	BigDecimal getTotalCost();

	Long getTotalTokens();

	LocalDateTime getLatestUsedAt();
}
