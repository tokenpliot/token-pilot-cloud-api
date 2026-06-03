package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(
	@NotBlank @Size(max = 100) String name,
	@NotBlank @Size(max = 50) String projectKey,
	@NotEmpty List<@NotBlank @Size(max = 20) String> environments,
	@NotBlank @Size(max = 100) String defaultModel
) {
}
