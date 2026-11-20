package com.tokenledgercloud.api.domain.rollout.breakglass.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** break-glass 조기 취소 요청. */
public record BreakGlassRevokeRequest(
		@NotBlank @Size(max = 500) String reason
) {
}
