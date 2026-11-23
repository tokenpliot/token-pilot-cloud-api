package com.tokenledgercloud.api.domain.rollout.breakglass.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.tokenledgercloud.api.domain.rollout.breakglass.BreakGlassGrant;
import com.tokenledgercloud.api.domain.rollout.breakglass.BreakGlassStatus;

/** break-glass grant 한 건. 시각은 UTC 오프셋을 붙여 돌려준다. */
public record BreakGlassGrantResponse(
		String grantId,
		String projectId,
		String environment,
		BreakGlassStatus status,
		String reason,
		String issuedBy,
		OffsetDateTime issuedAt,
		OffsetDateTime expiresAt,
		String revokedBy,
		String revokeReason,
		OffsetDateTime revokedAt
) {

	public static BreakGlassGrantResponse of(BreakGlassGrant grant, LocalDateTime now) {
		return new BreakGlassGrantResponse(
				grant.getId(),
				grant.getProjectId(),
				grant.getEnvironment(),
				grant.statusAt(now),
				grant.getReason(),
				grant.getIssuedBy(),
				utc(grant.getIssuedAt()),
				utc(grant.getExpiresAt()),
				grant.getRevokedBy(),
				grant.getRevokeReason(),
				utc(grant.getRevokedAt()));
	}

	private static OffsetDateTime utc(LocalDateTime value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}
}
