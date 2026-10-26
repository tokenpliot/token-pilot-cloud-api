package com.tokenledgercloud.api.domain.policy;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface OrganizationMembershipRepository extends Repository<OrganizationMembership, String> {
	Optional<OrganizationMembership> findByOrganizationIdAndUserId(String organizationId, String userId);
}
