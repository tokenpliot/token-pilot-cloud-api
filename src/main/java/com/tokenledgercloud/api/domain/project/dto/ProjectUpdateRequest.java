package com.tokenledgercloud.api.domain.project.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ProjectUpdateRequest(
	@NotBlank @Size(max = 100) String name,
	@NotEmpty List<@NotBlank @Size(max = 20) String> environments,
	@NotBlank @Size(max = 100) String defaultModel
) {
}
