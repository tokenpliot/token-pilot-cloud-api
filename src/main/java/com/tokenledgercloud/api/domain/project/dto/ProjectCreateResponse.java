package com.tokenledgercloud.api.domain.project.dto;

public record ProjectCreateResponse(
	String projectId,
	String name,
	String projectKey,
	String status
) {
}
