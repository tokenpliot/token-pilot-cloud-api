package com.tokenledgercloud.api.domain.shadow.repository;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import com.tokenledgercloud.api.domain.shadow.entity.DecisionExecutionEvent;

public interface DecisionExecutionRepository extends Repository<DecisionExecutionEvent, String> {
    DecisionExecutionEvent save(DecisionExecutionEvent event);
    Optional<DecisionExecutionEvent> findByProjectIdAndKeyHash(String project, String keyHash);

    @Query("""
        select e from DecisionExecutionEvent e where e.organizationId = :org and e.projectId = :project
            and (:environment is null or e.environment = :environment)
            and (:decisionId is null or e.decisionRecordId = :decisionId)
            and (:category is null
                or (:category = 'POLICY_DENIAL' and e.reportedDecision in ('DENY_POLICY', 'DENY_BUDGET'))
                or (:category = 'SERVER_UNAVAILABLE' and e.reportedDecision = 'CONTROL_PLANE_UNAVAILABLE')
                or (:category = 'CUSTOMER_OVERRIDE' and e.customerOverride <> 'NONE'))
        order by e.recordedAt desc, e.id desc
        """)
    List<DecisionExecutionEvent> list(String org, String project, String environment, String decisionId, String category, Pageable page);

    @Query("""
        select distinct e.decisionRecordId from DecisionExecutionEvent e where e.organizationId = :org
            and e.projectId = :project and e.decisionRecordId in :decisionIds and e.actualExecution = 'PROCEEDED'
        """)
    Set<String> proceeded(String org, String project, Collection<String> decisionIds);

    @Query("""
        select count(e) from DecisionExecutionEvent e where e.organizationId = :org and e.projectId = :project
            and (e.decisionRecordId in :decisionIds or (e.decisionRecordId is null and e.occurredAt >= :from and e.occurredAt < :to
                and (:environment is null or e.environment = :environment)))
            and (:category is null or (:category = 'SERVER_UNAVAILABLE' and e.reportedDecision = 'CONTROL_PLANE_UNAVAILABLE')
                or (:category = 'CUSTOMER_OVERRIDE' and e.customerOverride <> 'NONE'))
        """)
    int countForReport(String org, String project, Collection<String> decisionIds, LocalDateTime from, LocalDateTime to,
        String environment, String category);
}
