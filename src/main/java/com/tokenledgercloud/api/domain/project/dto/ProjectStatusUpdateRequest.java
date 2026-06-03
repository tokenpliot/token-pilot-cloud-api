package com.tokenledgercloud.api.domain.project.dto;

import jakarta.validation.constraints.NotBlank;

public record ProjectStatusUpdateRequest(
	@NotBlank String status
) {
}
