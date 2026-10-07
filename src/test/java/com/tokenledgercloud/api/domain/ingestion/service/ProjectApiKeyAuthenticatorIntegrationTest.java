package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures.SeededKey;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** The authenticator against real repositories, real BCrypt and the real status filter (H2). */
@SpringBootTest
class ProjectApiKeyAuthenticatorIntegrationTest {

	@Autowired
	private ProjectApiKeyAuthenticator authenticator;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectApiKeyRepository projectApiKeyRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private ProjectKeyFixtures fixtures;
	private Project project;

	@BeforeEach
	void setUp() {
		fixtures = new ProjectKeyFixtures(projectRepository, projectApiKeyRepository, passwordEncoder);
		project = fixtures.project(ProjectKeyFixtures.PROJECT_KEY, ProjectStatus.ACTIVE);
	}

	@AfterEach
	void tearDown() {
		fixtures.clear();
	}

	private AuthenticatedProjectApiKey authenticate(SeededKey key, String environment) {
		return authenticator.authenticate(key.raw(), ProjectKeyFixtures.PROJECT_KEY, environment);
	}

	@Test
	void activeKeyAuthenticatesAndRecordsLastUse() {
		SeededKey key = fixtures.key(project, "prod", "ACTIVE", null);
		assertThat(fixtures.reload(key).getLastUsedAt()).isNull();

		AuthenticatedProjectApiKey auth = authenticate(key, "prod");

		assertThat(auth.organizationId()).isEqualTo(ProjectKeyFixtures.ORG);
		assertThat(auth.projectId()).isEqualTo(project.getId());
		assertThat(auth.apiKeyId()).isEqualTo(key.row().getId());
		assertThat(fixtures.reload(key).getLastUsedAt()).isNotNull();
	}

	@Test
	void keysWithAnyStatusOtherThanActiveAreRejectedAsInvalid() {
		for (String status : new String[] {"REVOKED", "DISABLED", "INACTIVE", "EXPIRED"}) {
			SeededKey key = fixtures.key(project, "prod", status, null);

			assertThatThrownBy(() -> authenticate(key, "prod"))
				.as("status " + status)
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
					assertThat(e.getMessage()).isEqualTo("Invalid project API key.");
				});
			assertThat(fixtures.reload(key).getLastUsedAt()).isNull();
		}
	}

	@Test
	void revokedKeyIsIndistinguishableFromAnUnknownKey() {
		SeededKey revoked = fixtures.key(project, "prod", "REVOKED", null);

		ApiException forRevoked = catchApi(() -> authenticate(revoked, "prod"));
		ApiException forUnknown = catchApi(
			() -> authenticator.authenticate("tpk_live_" + "0".repeat(36), ProjectKeyFixtures.PROJECT_KEY, "prod"));

		assertThat(forRevoked.getErrorCode()).isEqualTo(forUnknown.getErrorCode());
		assertThat(forRevoked.getMessage()).isEqualTo(forUnknown.getMessage());
	}

	@Test
	void expiredKeyIs401AndDoesNotRecordUse() {
		SeededKey key = fixtures.key(project, "prod", "ACTIVE", LocalDateTime.now().minusMinutes(1));

		assertThatThrownBy(() -> authenticate(key, "prod")).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
			assertThat(e.getMessage()).isEqualTo("Project API key has expired.");
		});
		assertThat(fixtures.reload(key).getLastUsedAt()).isNull();
	}

	@Test
	void keyThatExpiresLaterStillWorks() {
		SeededKey key = fixtures.key(project, null, "ACTIVE", LocalDateTime.now().plusHours(1));

		assertThat(authenticate(key, "prod").projectId()).isEqualTo(project.getId());
	}

	@Test
	void environmentMismatchIs403() {
		SeededKey key = fixtures.key(project, "prod", "ACTIVE", null);

		assertThatThrownBy(() -> authenticate(key, "staging")).isInstanceOfSatisfying(ApiException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertThat(fixtures.reload(key).getLastUsedAt()).isNull();
	}

	@Test
	void keyWithoutAnEnvironmentWorksForAnyEnvironment() {
		SeededKey nullEnvironment = fixtures.key(project, null, "ACTIVE", null);
		SeededKey blankEnvironment = fixtures.key(project, "", "ACTIVE", null);

		for (SeededKey key : new SeededKey[] {nullEnvironment, blankEnvironment}) {
			assertThat(authenticate(key, "prod").projectId()).isEqualTo(project.getId());
			assertThat(authenticate(key, "staging").projectId()).isEqualTo(project.getId());
		}
	}

	@Test
	void keyOfAnotherProjectIs403AndUnknownProjectKeyIs404() {
		Project other = fixtures.project("other-project", ProjectStatus.ACTIVE);
		SeededKey keyOfOther = fixtures.key(other, null, "ACTIVE", null);
		SeededKey key = fixtures.key(project, null, "ACTIVE", null);

		assertThatThrownBy(() -> authenticate(keyOfOther, "prod")).isInstanceOfSatisfying(ApiException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertThatThrownBy(() -> authenticator.authenticate(key.raw(), "no-such-project", "prod"))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void archivedOrDeletedProjectsAre403() {
		SeededKey key = fixtures.key(project, null, "ACTIVE", null);

		for (ProjectStatus status : new ProjectStatus[] {ProjectStatus.ARCHIVED, ProjectStatus.DELETED}) {
			project.setStatus(status);
			projectRepository.save(project);

			assertThatThrownBy(() -> authenticate(key, "prod")).isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
				assertThat(e.getMessage()).isEqualTo("Project is not active.");
			});
		}
	}

	private ApiException catchApi(Runnable call) {
		try {
			call.run();
		} catch (ApiException exception) {
			return exception;
		}
		throw new AssertionError("expected an ApiException");
	}
}
