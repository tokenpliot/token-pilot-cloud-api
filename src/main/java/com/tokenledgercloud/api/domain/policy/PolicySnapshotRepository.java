package com.tokenledgercloud.api.domain.policy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Append-only application interface. No update/delete methods are exposed. */
public interface PolicySnapshotRepository extends Repository<PolicySnapshot, String> {
	PolicySnapshot save(PolicySnapshot snapshot);
	Optional<PolicySnapshot> findById(String id);
	Optional<PolicySnapshot> findByPolicyIdAndRevision(String policyId, long revision);
	List<PolicySnapshot> findByPolicyIdAndRevisionLessThanOrderByRevisionDesc(String policyId, long beforeVersion, Pageable pageable);
}
