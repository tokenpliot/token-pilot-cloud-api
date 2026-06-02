package com.tokenledgercloud.api.domain.ingestion.service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectApiKeyAuthenticator {

	private static final String ACTIVE_STATUS = "ACTIVE";
	private static final int MAX_KEY_PREFIX_LENGTH = 30;

	private final ProjectApiKeyRepository projectApiKeyRepository;
	private final ProjectRepository projectRepository;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public AuthenticatedProjectApiKey authenticate(String rawApiKey, String projectKey, String environment) {
		if (rawApiKey == null || rawApiKey.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Project API key is required.");
		}

		ProjectApiKey apiKey = projectApiKeyRepository
			.findByKeyPrefixInAndStatus(prefixCandidates(rawApiKey), ACTIVE_STATUS)
			.stream()
			.filter(candidate -> passwordEncoder.matches(rawApiKey, candidate.getKeyHash()))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Invalid project API key."));

		validateExpiration(apiKey);
		validateEnvironment(apiKey, environment);
		Project project = validateProject(apiKey, projectKey);

		apiKey.setLastUsedAt(LocalDateTime.now());

		return new AuthenticatedProjectApiKey(
			apiKey.getOrganizationId(),
			project.getId(),
			apiKey.getId(),
			apiKey.getEnvironment()
		);
	}

	private Set<String> prefixCandidates(String rawApiKey) {
		Set<String> candidates = new LinkedHashSet<>();
		candidates.add(rawApiKey.length() <= MAX_KEY_PREFIX_LENGTH
			? rawApiKey
			: rawApiKey.substring(0, MAX_KEY_PREFIX_LENGTH));

		int lastSeparatorIndex = Math.max(rawApiKey.lastIndexOf('_'), rawApiKey.lastIndexOf('.'));
		if (lastSeparatorIndex > 0) {
			String visiblePrefix = rawApiKey.substring(0, Math.min(lastSeparatorIndex, MAX_KEY_PREFIX_LENGTH));
			candidates.add(visiblePrefix);
		}

		return candidates;
	}

	private void validateExpiration(ProjectApiKey apiKey) {
		if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Project API key has expired.");
		}
	}

	private void validateEnvironment(ProjectApiKey apiKey, String environment) {
		if (apiKey.getEnvironment() != null && !apiKey.getEnvironment().isBlank()
			&& !apiKey.getEnvironment().equals(environment)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Project API key is not allowed for this environment.");
		}
	}

	private Project validateProject(ProjectApiKey apiKey, String projectKey) {
		Project project = projectRepository.findByOrganizationIdAndProjectKey(apiKey.getOrganizationId(), projectKey)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Project was not found for projectKey."));

		if (!project.getId().equals(apiKey.getProjectId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Project API key is not allowed for this project.");
		}
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Project is not active.");
		}

		return project;
	}
}
