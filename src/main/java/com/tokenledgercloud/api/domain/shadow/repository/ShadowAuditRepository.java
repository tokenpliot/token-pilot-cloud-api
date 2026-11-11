package com.tokenledgercloud.api.domain.shadow.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

}
