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

/**
 * Authenticates a project API key for the ingestion endpoints. Checks run in this order and the first failure
 * ends the request (every failure is non-retryable and the messages never contain the key):
 *
 * <ol>
 * <li>key missing or blank: 401</li>
 * <li>an ACTIVE key whose prefix candidates and BCrypt hash match: otherwise 401 "Invalid project API key."
 *     Unknown, wrong, revoked and disabled keys all look the same on purpose. Any status other than ACTIVE
 *     counts as revoked; there is no separate revoked-at or reason.</li>
 * <li>expiry: {@code expiresAt} before the server's local {@code now()}: 401 "expired". A null expiry never expires.</li>
 * <li>environment: if the key has one it must equal the request's exactly (case-sensitive): otherwise 403.
 *     A key whose environment is null or blank is valid for every environment, and the request's environment
 *     is then used as sent.</li>
 * <li>project: looked up by (key's organization, projectKey): missing 404; not the key's project 403;
 *     not ACTIVE 403</li>
 * </ol>
 * {@code lastUsedAt} is updated only when every check passes.
 *
 * <p>Not verified here: scope or permissions (the key table has no scope; any ACTIVE key may call both ingestion
 * endpoints), revocation time or reason, request rate, and the time zone of {@code expiresAt}.
 * See docs/api/ingestion-api-key-verification.md.
 */
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
