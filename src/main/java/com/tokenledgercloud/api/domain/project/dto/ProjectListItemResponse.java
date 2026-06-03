package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

public record ProjectListItemResponse(
	String projectId,
	String projectName,
	List<String> environments,
	String defaultModel,
	String status
) {
}
