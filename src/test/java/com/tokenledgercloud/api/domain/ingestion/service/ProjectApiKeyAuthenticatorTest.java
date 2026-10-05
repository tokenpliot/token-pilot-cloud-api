package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** Pins what the authenticator checks today, in order, with the exact responses. */
@ExtendWith(MockitoExtension.class)
class ProjectApiKeyAuthenticatorTest {

	private static final String RAW_KEY = "tpk_live_0123456789abcdef";
	private static final String PROJECT_KEY = "support-copilot";

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

	@Mock
	private ProjectApiKeyRepository projectApiKeyRepository;

	@Mock
	private ProjectRepository projectRepository;

	private ProjectApiKeyAuthenticator authenticator;

	@BeforeEach
	void setUp() {
		authenticator = new ProjectApiKeyAuthenticator(projectApiKeyRepository, projectRepository, encoder);
	}

	private ProjectApiKey key(String environment, LocalDateTime expiresAt) {
		return ProjectApiKey.builder()
			.id("key-1").organizationId("org-1").projectId("project-1")
			.environment(environment).name("k").keyPrefix("tpk_live").keyHash(encoder.encode(RAW_KEY))
			.status("ACTIVE").expiresAt(expiresAt)
			.build();
	}

	private Project project(String id, ProjectStatus status) {
		return Project.builder().id(id).organizationId("org-1").projectKey(PROJECT_KEY).name("p").status(status)
			.build();
	}

	private void givenActiveKey(ProjectApiKey key) {
		given(projectApiKeyRepository.findByKeyPrefixInAndStatus(any(), eq("ACTIVE"))).willReturn(List.of(key));
	}

	private void givenProject(Project project) {
		given(projectRepository.findByOrganizationIdAndProjectKey("org-1", PROJECT_KEY))
			.willReturn(Optional.ofNullable(project));
	}

	private void assertRejected(ThrowingCall call, ErrorCode code, String message) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(code);
			assertThat(e.getMessage()).isEqualTo(message);
			assertThat(e.getMessage()).doesNotContain(RAW_KEY).doesNotContain("0123456789abcdef");
			assertThat(code.isRetryable()).isFalse();
		});
	}

	private interface ThrowingCall {
		void run();
	}

	// --- 1. missing key ---

	@Test
	void missingOrBlankKeyIs401WithoutTouchingTheDatabase() {
		assertRejected(() -> authenticator.authenticate(null, PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Project API key is required.");
		assertRejected(() -> authenticator.authenticate("", PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Project API key is required.");
		assertRejected(() -> authenticator.authenticate("   ", PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Project API key is required.");

		verifyNoInteractions(projectApiKeyRepository, projectRepository);
	}

	// --- 2. unknown / wrong / inactive key ---

	@Test
	void unknownKeyIs401() {
		given(projectApiKeyRepository.findByKeyPrefixInAndStatus(any(), any())).willReturn(List.of());

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Invalid project API key.");
	}

	@Test
	void sameVisiblePrefixButWrongSecretIs401() {
		givenActiveKey(key(null, null));

		assertRejected(() -> authenticator.authenticate("tpk_live_ffffffffffffffff", PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Invalid project API key.");
	}

	@Test
	void onlyActiveKeysAreEverLookedUp() {
		given(projectApiKeyRepository.findByKeyPrefixInAndStatus(any(), any())).willReturn(List.of());

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Invalid project API key.");

		verify(projectApiKeyRepository).findByKeyPrefixInAndStatus(any(), eq("ACTIVE"));
	}

	@SuppressWarnings("unchecked")
	private Collection<String> candidatesFor(String rawKey) {
		given(projectApiKeyRepository.findByKeyPrefixInAndStatus(any(), any())).willReturn(List.of());
		assertThatThrownBy(() -> authenticator.authenticate(rawKey, PROJECT_KEY, "prod"))
			.isInstanceOf(ApiException.class);
		ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(projectApiKeyRepository).findByKeyPrefixInAndStatus(captor.capture(), eq("ACTIVE"));
		return captor.getValue();
	}

	@Test
	void prefixCandidatesAreTheKeyItselfUpTo30CharsAndTheTextBeforeTheLastSeparator() {
		// short key: the whole key, and the part before the last '_'
		assertThat(candidatesFor("tp_abc")).containsExactlyInAnyOrder("tp_abc", "tp");
	}

	@Test
	void longKeyUsesItsFirst30CharsAndTheVisiblePrefixBeforeTheLastSeparator() {
		String longKey = "tpk_live_" + "a".repeat(40);

		assertThat(candidatesFor(longKey)).containsExactlyInAnyOrder(longKey.substring(0, 30), "tpk_live");
	}

	// --- 3. expiry ---

	@Test
	void expiredKeyIs401WithADifferentMessageThanAnInvalidKey() {
		givenActiveKey(key(null, LocalDateTime.now().minusMinutes(1)));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.UNAUTHORIZED, "Project API key has expired.");
	}

	@Test
	void futureAndNullExpiryAreAccepted() {
		givenProject(project("project-1", ProjectStatus.ACTIVE));

		givenActiveKey(key(null, LocalDateTime.now().plusHours(1)));
		assertThat(authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod").projectId()).isEqualTo("project-1");

		givenActiveKey(key(null, null));
		assertThat(authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod").projectId()).isEqualTo("project-1");
	}

	// --- 4. environment ---

	@Test
	void environmentMismatchIs403() {
		givenActiveKey(key("prod", null));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "staging"),
			ErrorCode.FORBIDDEN, "Project API key is not allowed for this environment.");
	}

	@Test
	void environmentComparisonIsCaseSensitive() {
		givenActiveKey(key("prod", null));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "Prod"),
			ErrorCode.FORBIDDEN, "Project API key is not allowed for this environment.");
	}

	@Test
	void matchingEnvironmentIsAccepted() {
		givenActiveKey(key("prod", null));
		givenProject(project("project-1", ProjectStatus.ACTIVE));

		AuthenticatedProjectApiKey auth = authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod");

		assertThat(auth.environment()).isEqualTo("prod");
	}

	@Test
	void keyWithoutAnEnvironmentIsValidForEveryEnvironment() {
		givenProject(project("project-1", ProjectStatus.ACTIVE));

		for (String keyEnvironment : new String[] {null, "", "  "}) {
			givenActiveKey(key(keyEnvironment, null));
			for (String requested : new String[] {"prod", "staging", "anything-else"}) {
				AuthenticatedProjectApiKey auth = authenticator.authenticate(RAW_KEY, PROJECT_KEY, requested);

				// the key's own environment is what comes back; the request's value is not bound to it
				assertThat(auth.environment()).isEqualTo(keyEnvironment);
				assertThat(auth.projectId()).isEqualTo("project-1");
			}
		}
	}

	// --- 5. project ---

	@Test
	void unknownProjectKeyIs404() {
		givenActiveKey(key(null, null));
		givenProject(null);

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.NOT_FOUND, "Project was not found for projectKey.");
	}

	@Test
	void projectBelongingToAnotherProjectIs403() {
		givenActiveKey(key(null, null));
		givenProject(project("another-project", ProjectStatus.ACTIVE));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.FORBIDDEN, "Project API key is not allowed for this project.");
	}

	@Test
	void archivedAndDeletedProjectsAre403() {
		givenActiveKey(key(null, null));

		for (ProjectStatus status : new ProjectStatus[] {ProjectStatus.ARCHIVED, ProjectStatus.DELETED}) {
			givenProject(project("project-1", status));

			assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
				ErrorCode.FORBIDDEN, "Project is not active.");
		}
	}

	// --- the order of the checks is part of the contract ---

	@Test
	void expiryIsCheckedBeforeEnvironment() {
		givenActiveKey(key("prod", LocalDateTime.now().minusMinutes(1)));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "staging"),
			ErrorCode.UNAUTHORIZED, "Project API key has expired.");
		verifyNoInteractions(projectRepository);
	}

	@Test
	void environmentIsCheckedBeforeTheProjectLookup() {
		givenActiveKey(key("prod", null));

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "staging"),
			ErrorCode.FORBIDDEN, "Project API key is not allowed for this environment.");
		verifyNoInteractions(projectRepository);
	}

	@Test
	void unknownProjectIsReportedBeforeAProjectMismatchCanBe() {
		givenActiveKey(key(null, null));
		givenProject(null);

		assertRejected(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"),
			ErrorCode.NOT_FOUND, "Project was not found for projectKey.");
	}

	// --- lastUsedAt ---

	@Test
	void lastUsedAtIsUpdatedOnlyWhenEveryCheckPasses() {
		ProjectApiKey expired = key(null, LocalDateTime.now().minusMinutes(1));
		givenActiveKey(expired);
		assertThatThrownBy(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"))
			.isInstanceOf(ApiException.class);
		assertThat(expired.getLastUsedAt()).isNull();

		ProjectApiKey wrongEnvironment = key("prod", null);
		givenActiveKey(wrongEnvironment);
		assertThatThrownBy(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "staging"))
			.isInstanceOf(ApiException.class);
		assertThat(wrongEnvironment.getLastUsedAt()).isNull();

		ProjectApiKey wrongProject = key(null, null);
		givenActiveKey(wrongProject);
		givenProject(project("another-project", ProjectStatus.ACTIVE));
		assertThatThrownBy(() -> authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod"))
			.isInstanceOf(ApiException.class);
		assertThat(wrongProject.getLastUsedAt()).isNull();

		ProjectApiKey good = key(null, null);
		givenActiveKey(good);
		givenProject(project("project-1", ProjectStatus.ACTIVE));
		authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod");
		assertThat(good.getLastUsedAt()).isNotNull();
	}

	@Test
	void successReturnsTheKeysOrganizationProjectAndKeyId() {
		givenActiveKey(key("prod", null));
		givenProject(project("project-1", ProjectStatus.ACTIVE));

		AuthenticatedProjectApiKey auth = authenticator.authenticate(RAW_KEY, PROJECT_KEY, "prod");

		assertThat(auth).isEqualTo(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
		verify(projectRepository, never()).save(any());
	}
}
