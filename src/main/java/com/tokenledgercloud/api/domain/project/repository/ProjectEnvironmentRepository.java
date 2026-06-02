package com.tokenledgercloud.api.domain.project.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;

public interface ProjectEnvironmentRepository extends JpaRepository<ProjectEnvironment, String> {

	List<ProjectEnvironment> findByProjectId(String projectId);

	List<ProjectEnvironment> findByProjectIdIn(Collection<String> projectIds);
}
