package com.tokenledgercloud.api.domain.shadow.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import com.tokenledgercloud.api.domain.shadow.entity.ShadowAudit;
import com.tokenledgercloud.api.domain.shadow.entity.ShadowAudit.Kind;

public interface ShadowAuditRepository extends Repository<ShadowAudit, String> {
    ShadowAudit save(ShadowAudit audit);
    Optional<ShadowAudit> findByIdAndOrganizationIdAndProjectIdAndKind(String id, String org, String project, Kind kind);
    Optional<ShadowAudit> findByOrganizationIdAndProjectIdAndKindAndKeyHash(String org, String project, Kind kind, String keyHash);
    Optional<ShadowAudit> findFirstByOrganizationIdAndProjectIdAndKindAndEnvironmentKeyOrderByRevisionDesc(
        String org, String project, Kind kind, String environmentKey);
    List<ShadowAudit> findByOrganizationIdAndProjectIdAndKindOrderByCreatedAtDescIdDesc(String org, String project, Kind kind, Pageable page);
    List<ShadowAudit> findByOrganizationIdAndProjectIdAndKindAndEnvironmentKeyOrderByRevisionDesc(
        String org, String project, Kind kind, String environmentKey, Pageable page);

    @Query("""
        select a from ShadowAudit a where a.organizationId = :org and a.projectId = :project
            and a.parentRunId = :runId and a.kind = 'EXECUTION'
            and (:category is null or (:category = 'POLICY_DENIAL' and a.policyDenial = true)
                or (:category = 'SERVER_UNAVAILABLE' and a.serverUnavailable = true)
                or (:category = 'CUSTOMER_OVERRIDE' and a.customerOverride = true))
        order by a.createdAt desc, a.id desc
        """)
    List<ShadowAudit> feedback(String org, String project, String runId, String category, Pageable page);

    @Query("""
        select count(a) from ShadowAudit a where a.organizationId = :org and a.projectId = :project
            and a.parentRunId = :runId and a.kind = 'EXECUTION'
            and (:category is null or (:category = 'SERVER_UNAVAILABLE' and a.serverUnavailable = true)
                or (:category = 'CUSTOMER_OVERRIDE' and a.customerOverride = true))
        """)
    int countFeedback(String org, String project, String runId, String category);
}
