package com.tokenledgercloud.api.domain.policy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CustomerPolicyRepository extends JpaRepository<CustomerPolicy, String> {
	Optional<CustomerPolicy> findByOrganizationIdAndProjectIdAndEnvironmentKey(String organizationId, String projectId, String environmentKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from CustomerPolicy p where p.organizationId = :organizationId and p.projectId = :projectId and p.environmentKey = :environmentKey")
	Optional<CustomerPolicy> lockByScope(String organizationId, String projectId, String environmentKey);
}
