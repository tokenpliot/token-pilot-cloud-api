package com.tokenledgercloud.api.domain.policy;

import java.time.Instant;
import java.time.ZoneOffset;

public record PolicyVersionView(String policyId, long revision, String version, String snapshotId,
	String organizationId, String projectId, String environment, PolicyRules rules,
	String actorType, String actorId, Instant changedAt, String changeType, String reason,
	Long restoredFromVersion, String checksum, String snapshotJson) {
	static PolicyVersionView from(PolicySnapshot s) {
		return new PolicyVersionView(s.getPolicyId(), s.getRevision(), s.getVersion(), s.getId(),
			s.getOrganizationId(), s.getProjectId(), s.getEnvironment(), s.rules(), s.getActorType(), s.getActorId(),
			s.getCreatedAt().toInstant(ZoneOffset.UTC), s.getChangeType(), s.getReason(),
			s.getRestoredFromVersion(), s.getChecksum(), s.getSnapshotJson());
	}
}
