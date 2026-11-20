package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakGlassGrantRepository extends JpaRepository<BreakGlassGrant, String> {

	Optional<BreakGlassGrant> findByActiveScopeKey(String activeScopeKey);

	// 감사 이력: 최근 발급 순.
	List<BreakGlassGrant> findByOrganizationIdAndProjectIdOrderByIssuedAtDesc(
			String organizationId, String projectId, Pageable pageable);

	List<BreakGlassGrant> findByOrganizationIdAndProjectIdAndEnvironmentOrderByIssuedAtDesc(
			String organizationId, String projectId, String environment, Pageable pageable);
}
