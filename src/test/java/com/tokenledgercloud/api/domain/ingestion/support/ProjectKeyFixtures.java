package com.tokenledgercloud.api.domain.ingestion.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;

/** Seeds projects and hashed project API keys for integration tests and removes only what it created. */
public final class ProjectKeyFixtures {

	public static final String ORG = "org-1";
	public static final String PROJECT_KEY = "support-copilot";

	/** A seeded key: the raw value a client would send, and the stored row. */
	public record SeededKey(String raw, ProjectApiKey row) {
	}

	private final ProjectRepository projectRepository;
	private final ProjectApiKeyRepository projectApiKeyRepository;
	private final PasswordEncoder passwordEncoder;
	private final List<String> projectIds = new ArrayList<>();
	private final List<String> keyIds = new ArrayList<>();

	public ProjectKeyFixtures(
		ProjectRepository projectRepository,
		ProjectApiKeyRepository projectApiKeyRepository,
		PasswordEncoder passwordEncoder
	) {
		this.projectRepository = projectRepository;
		this.projectApiKeyRepository = projectApiKeyRepository;
		this.passwordEncoder = passwordEncoder;
	}

	public Project project(String projectKey, ProjectStatus status) {
		Project saved = projectRepository.save(Project.builder()
			.organizationId(ORG)
			.projectKey(projectKey)
			.name("Project " + projectKey)
			.status(status)
			.build());
		projectIds.add(saved.getId());
		return saved;
	}

	public SeededKey key(Project project, String environment, String status, LocalDateTime expiresAt) {
		String raw = "tpk_live_" + UUID.randomUUID();
		ProjectApiKey saved = projectApiKeyRepository.save(ProjectApiKey.builder()
			.organizationId(project.getOrganizationId())
			.projectId(project.getId())
			.environment(environment)
			.name("test key")
			.keyPrefix(raw.substring(0, 30))
			.keyHash(passwordEncoder.encode(raw))
			.status(status)
			.expiresAt(expiresAt)
			.build());
		keyIds.add(saved.getId());
		return new SeededKey(raw, saved);
	}

	public ProjectApiKey reload(SeededKey key) {
		return projectApiKeyRepository.findById(key.row().getId()).orElseThrow();
	}

	public void clear() {
		projectApiKeyRepository.deleteAllById(keyIds);
		projectRepository.deleteAllById(projectIds);
		keyIds.clear();
		projectIds.clear();
	}
}
