package com.tokenledgercloud.api.domain.rollout.breakglass.dto;

import java.time.OffsetDateTime;

/** 지금 이 범위에 유효한 break-glass가 있는지. 없으면 grant는 null이다. */
public record BreakGlassActiveResponse(
		String projectId,
		String environment,
		boolean active,
		BreakGlassGrantResponse grant,
		OffsetDateTime checkedAt
) {
}
