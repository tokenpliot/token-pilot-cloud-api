package com.tokenledgercloud.api.domain.policy;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PolicyRequestContextRepository extends Repository<PolicyRequestContext, String> {
	PolicyRequestContext save(PolicyRequestContext context);
	Optional<PolicyRequestContext> findByOrganizationIdAndProjectIdAndEnvironmentAndRequestId(
		String org, String project, String environment, String requestId);
}
