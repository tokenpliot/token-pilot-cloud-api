package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchItemResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionItemStatus;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures.SeededKey;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** An invalid API key stores nothing, whether the request is a single event or a batch. Real auth, real H2. */
@SpringBootTest
class IngestionApiKeyFailureIntegrationTest {

	private static final String PROJECT_KEY = ProjectKeyFixtures.PROJECT_KEY;

	@Autowired
	private IngestionService ingestionService;

	@Autowired
	private UsageLogRepository usageLogRepository;

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
		usageLogRepository.deleteAll();
		fixtures = new ProjectKeyFixtures(projectRepository, projectApiKeyRepository, passwordEncoder);
		project = fixtures.project(PROJECT_KEY, ProjectStatus.ACTIVE);
	}

	@AfterEach
	void tearDown() {
		usageLogRepository.deleteAll();
		fixtures.clear();
	}

	private static IngestionEventItemRequest item(String requestId, Map<String, Object> metadata) {
		return new IngestionEventItemRequest(
			requestId, "openai", "gpt-4o-mini",
			100L, 40L, 0L, null, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", null, metadata,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null
		);
	}

	private static IngestionEventRequest event(String requestId, String environment) {
		return new IngestionEventRequest(
			PROJECT_KEY, environment, requestId, "openai", "gpt-4o-mini",
			100L, 40L, 0L, null, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", null, null,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null
		);
	}

	/** Valid items plus one with a forbidden metadata key (dropped, not rejected): none of them may be reached with a bad key. */
	private static IngestionBatchRequest batch(String environment) {
		return new IngestionBatchRequest(PROJECT_KEY, environment, List.of(
			item("req-1", null),
			item("req-2", Map.of("prompt", "SECRET-TEXT")),
			item("req-3", Map.of("feature", "summary"))));
	}

	private void assertNothingStored() {
		assertThat(usageLogRepository.count()).isZero();
	}

	// --- batch ---

	@Test
	void batchWithAnUnknownKeyStoresNothingAndReturnsNoItemResults() {
		assertThatThrownBy(() -> ingestionService.collectBatch("tpk_live_" + "0".repeat(36), batch("prod")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
		assertNothingStored();
	}

	@Test
	void batchWithAMissingKeyStoresNothing() {
		assertThatThrownBy(() -> ingestionService.collectBatch(null, batch("prod")))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
				assertThat(e.getMessage()).isEqualTo("Project API key is required.");
			});
		assertNothingStored();
	}

	@Test
	void batchWithARevokedKeyStoresNothing() {
		SeededKey revoked = fixtures.key(project, "prod", "REVOKED", null);

		assertThatThrownBy(() -> ingestionService.collectBatch(revoked.raw(), batch("prod")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
		assertNothingStored();
		assertThat(fixtures.reload(revoked).getLastUsedAt()).isNull();
	}

	@Test
	void batchWithAnExpiredKeyStoresNothing() {
		SeededKey expired = fixtures.key(project, "prod", "ACTIVE", LocalDateTime.now().minusMinutes(1));

		assertThatThrownBy(() -> ingestionService.collectBatch(expired.raw(), batch("prod")))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
				assertThat(e.getMessage()).isEqualTo("Project API key has expired.");
			});
		assertNothingStored();
	}

	@Test
	void batchForTheWrongEnvironmentStoresNothing() {
		SeededKey prodOnly = fixtures.key(project, "prod", "ACTIVE", null);

		assertThatThrownBy(() -> ingestionService.collectBatch(prodOnly.raw(), batch("staging")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertNothingStored();
	}

	@Test
	void batchWithAKeyOfAnotherProjectStoresNothing() {
		Project other = fixtures.project("other-project", ProjectStatus.ACTIVE);
		SeededKey keyOfOther = fixtures.key(other, null, "ACTIVE", null);

		assertThatThrownBy(() -> ingestionService.collectBatch(keyOfOther.raw(), batch("prod")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertNothingStored();
	}

	@Test
	void batchForAnUnknownProjectKeyStoresNothing() {
		SeededKey key = fixtures.key(project, null, "ACTIVE", null);

		assertThatThrownBy(() -> ingestionService.collectBatch(key.raw(),
			new IngestionBatchRequest("no-such-project", "prod", List.of(item("req-1", null)))))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertNothingStored();
	}

	@Test
	void batchForAnInactiveProjectStoresNothing() {
		SeededKey key = fixtures.key(project, null, "ACTIVE", null);
		project.setStatus(ProjectStatus.ARCHIVED);
		projectRepository.save(project);

		assertThatThrownBy(() -> ingestionService.collectBatch(key.raw(), batch("prod")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertNothingStored();
	}

	// --- single event ---

	@Test
	void singleEventWithABadKeyStoresNothing() {
		SeededKey revoked = fixtures.key(project, "prod", "REVOKED", null);
		SeededKey expired = fixtures.key(project, "prod", "ACTIVE", LocalDateTime.now().minusMinutes(1));

		for (String raw : new String[] {null, "", "tpk_live_" + "0".repeat(36), revoked.raw(), expired.raw()}) {
			assertThatThrownBy(() -> ingestionService.collectEvent(raw, event("req-1", "prod")))
				.isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
		}
		assertNothingStored();
	}

	// --- the same requests succeed with a valid key, and are attributed to the key's project ---

	@Test
	void validKeyStoresTheBatchAttributedToTheKeysProjectAndRecordsUse() {
		SeededKey key = fixtures.key(project, "prod", "ACTIVE", null);

		IngestionBatchResponse response = ingestionService.collectBatch(key.raw(), batch("prod"));

		// the forbidden-key item is stored without that key once the key is accepted
		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.CREATED, IngestionItemStatus.CREATED);
		List<UsageLog> rows = usageLogRepository.findAll();
		assertThat(rows).hasSize(3).allSatisfy(row -> {
			assertThat(row.getOrganizationId()).isEqualTo(ProjectKeyFixtures.ORG);
			assertThat(row.getProjectId()).isEqualTo(project.getId());
			assertThat(row.getApiKeyId()).isEqualTo(key.row().getId());
			assertThat(row.getEnvironment()).isEqualTo("prod");
		});
		assertThat(fixtures.reload(key).getLastUsedAt()).isNotNull();
	}

	// --- keys without an environment ---

	@Test
	void keyWithoutAnEnvironmentStoresEventsUnderWhateverEnvironmentTheRequestSends() {
		SeededKey anyEnvironment = fixtures.key(project, null, "ACTIVE", null);
		SeededKey blankEnvironment = fixtures.key(project, "", "ACTIVE", null);

		ingestionService.collectEvent(anyEnvironment.raw(), event("req-prod", "prod"));
		ingestionService.collectEvent(anyEnvironment.raw(), event("req-staging", "staging"));
		ingestionService.collectEvent(blankEnvironment.raw(), event("req-qa", "qa"));

		assertThat(usageLogRepository.findAll())
			.extracting(UsageLog::getRequestId, UsageLog::getEnvironment)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple("req-prod", "prod"),
				org.assertj.core.groups.Tuple.tuple("req-staging", "staging"),
				org.assertj.core.groups.Tuple.tuple("req-qa", "qa"));
	}
}
