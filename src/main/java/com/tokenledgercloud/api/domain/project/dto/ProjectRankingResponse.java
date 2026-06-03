package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

public record ProjectRankingResponse(
	List<ProjectRankingItemResponse> items
) {
}
