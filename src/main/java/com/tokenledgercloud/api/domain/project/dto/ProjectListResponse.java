package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

public record ProjectListResponse(
	List<ProjectListItemResponse> items
) {
}
