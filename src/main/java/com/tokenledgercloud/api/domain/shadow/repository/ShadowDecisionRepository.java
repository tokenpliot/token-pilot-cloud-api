package com.tokenledgercloud.api.domain.shadow.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import com.tokenledgercloud.api.domain.decision.DecisionRecord;
import com.tokenledgercloud.api.domain.policy.PolicyRequestContext;

public interface ShadowDecisionRepository extends Repository<DecisionRecord, String> {
    interface Row {
        DecisionRecord getDecision();
        PolicyRequestContext getContext();
    }
    @Query("""
        select d as decision, c as context from DecisionRecord d, PolicyRequestContext c
        where d.policyRequestContextId = c.id and d.organizationId = :org and d.projectId = :project
            and c.organizationId = :org and c.projectId = :project
            and (c.projectPolicySnapshotId = :baseline or c.environmentPolicySnapshotId = :baseline)
            and d.evaluatedAt >= :from and d.evaluatedAt < :to
        order by d.evaluatedAt, d.id
        """)
    List<Row> samples(String org, String project, String baseline, LocalDateTime from, LocalDateTime to, Pageable page);

    @Query("""
        select d as decision, c as context from DecisionRecord d, PolicyRequestContext c
        where d.policyRequestContextId = c.id and d.id = :id and d.organizationId = :org and d.projectId = :project
            and c.organizationId = :org and c.projectId = :project
        """)
    Optional<Row> scoped(String id, String org, String project);
}
