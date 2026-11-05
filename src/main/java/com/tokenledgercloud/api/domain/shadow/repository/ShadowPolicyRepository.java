package com.tokenledgercloud.api.domain.shadow.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import com.tokenledgercloud.api.domain.policy.PolicySnapshot;

public interface ShadowPolicyRepository extends Repository<PolicySnapshot, String> {
    @Query("""
        select s from PolicySnapshot s, CustomerPolicy p
        where p.organizationId = :org and p.projectId = :project
            and s.policyId = p.id and s.revision = p.currentVersion
        order by p.environmentKey
        """)
    List<PolicySnapshot> current(String org, String project);
}
