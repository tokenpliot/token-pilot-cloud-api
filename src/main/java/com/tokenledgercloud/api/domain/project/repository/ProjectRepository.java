package com.tokenledgercloud.api.domain.project.repository;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.tokenledgercloud.api.domain.project.entity.Project;

public interface ProjectRepository extends JpaRepository<Project, String> {

        Optional<Project> findByOrganizationIdAndProjectKey(String organizationId, String projectKey);

        boolean existsByOrganizationIdAndProjectKey(String organizationId, String projectKey);

        Optional<Project> findByIdAndOrganizationId(String id, String organizationId);

        List<Project> findByIdInAndOrganizationId(Iterable<String> ids, String organizationId);

        @Query("""
                select distinct p
                from Project p
                left join ProjectEnvironment e on e.projectId = p.id
                where p.organizationId = :organizationId
                  and (:environment is null or e.environment = :environment)
                  and (:status is null or p.status = :status)
                order by p.createdAt desc
        """)
        List<Project> findProjects(String organizationId, String environment, String status);
}
