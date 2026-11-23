package com.tokenledgercloud.api.domain.rollout.breakglass.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** break-glass 발급 요청. 만료 시간은 필수이고 최대 24시간이다. */
public record BreakGlassIssueRequest(
		@NotBlank @Size(max = 36) String projectId,
		@NotBlank @Size(max = 20) String environment,
		// 사후 감사에서 왜 우회했는지 설명할 수 있어야 하므로 비워 둘 수 없다.
		@NotBlank @Size(max = 500) String reason,
		@NotNull @Min(1) @Max(1440) Integer durationMinutes
) {
}
