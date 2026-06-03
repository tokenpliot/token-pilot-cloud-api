package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

public record ProjectMutationResponse(
	String projectId,
	String name,
	String projectKey,
	String status,
	List<String> environments,
	String defaultModel
) {
}
