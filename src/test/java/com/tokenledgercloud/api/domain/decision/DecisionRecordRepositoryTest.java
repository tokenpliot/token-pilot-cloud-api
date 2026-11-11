package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.policy.PolicyContextService;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.global.exception.ApiException;

/**
 * decision_records의 멱등 범위와 requestId 길이 확장을 확인한다. H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다.
 * 그래서 V8 SQL 자체는 여기서 검증되지 않는다(MySQL에서 따로 확인해야 한다).
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:decision-records;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false"
})
class DecisionRecordRepositoryTest {

	@Autowired DecisionRecordRepository records;
	@Autowired PolicyContextService contexts;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		for (String table : List.of("decision_records", "policy_request_contexts", "project_environments", "projects")) {
			jdbc.update("delete from " + table);
		}
		project("project-a");
		project("project-b");
	}

	private void project(String id) {
		projects.saveAndFlush(Project.builder().id(id).organizationId("org-1").projectKey(id).name(id).status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId(id).environment("prod").build());
	}

	private static AuthenticatedProjectApiKey key(String projectId) {
		return new AuthenticatedProjectApiKey("org-1", projectId, "key-" + projectId, "prod");
	}

	private String context(String projectId, String requestId) {
		return contexts.capture(key(projectId), "prod", requestId, null).context().getId();
	}

	private DecisionRecord record(String projectId, String contextId, String idempotencyKey) {
		return DecisionRecord.of(DecisionRecord.newId(), "org-1", projectId, "key-" + projectId, contextId, idempotencyKey, "f".repeat(64),
			EnforcementMode.ADVISORY, DecisionOutcome.ALLOW, "advisory-v1", LocalDateTime.of(2026, 11, 9, 12, 0),
			"{\"estimatedInputTokens\":1000}", "{\"outcome\":\"ALLOW\"}");
	}

	@Test
	void savesAndFindsByProjectAndIdempotencyKey() {
		String ctx = context("project-a", "req-1");
		DecisionRecord saved = records.saveAndFlush(record("project-a", ctx, "dec:req-1"));

		assertThat(records.findByProjectIdAndIdempotencyKey("project-a", "dec:req-1")).get()
			.satisfies(found -> {
				assertThat(found.getId()).isEqualTo(saved.getId());
				assertThat(found.decisionId()).isEqualTo("dec-" + saved.getId());
				assertThat(found.getOutcome()).isEqualTo(DecisionOutcome.ALLOW);
				assertThat(found.getClientMode()).isEqualTo(EnforcementMode.ADVISORY);
				assertThat(found.getResponseJson()).contains("ALLOW");
			});
		assertThat(records.findByPolicyRequestContextId(ctx)).isPresent();
	}

	@Test
	void sameKeyInAnotherProjectDoesNotCollide() {
		records.saveAndFlush(record("project-a", context("project-a", "req-1"), "dec:req-1"));

		DecisionRecord other = records.saveAndFlush(record("project-b", context("project-b", "req-1"), "dec:req-1"));

		assertThat(other.getProjectId()).isEqualTo("project-b");
		assertThat(records.findByProjectIdAndIdempotencyKey("project-a", "dec:req-1")).isPresent();
		assertThat(records.findByProjectIdAndIdempotencyKey("project-b", "dec:req-1")).isPresent();
	}

	@Test
	void sameKeyInTheSameProjectIsRejectedByTheDatabase() {
		records.saveAndFlush(record("project-a", context("project-a", "req-1"), "dec:shared-key"));
		String second = context("project-a", "req-2");

		assertThatThrownBy(() -> records.saveAndFlush(record("project-a", second, "dec:shared-key")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void secondDecisionForTheSameRequestIsRejectedEvenWithAnotherKey() {
		String ctx = context("project-a", "req-1");
		records.saveAndFlush(record("project-a", ctx, "dec:first-key"));

		assertThatThrownBy(() -> records.saveAndFlush(record("project-a", ctx, "dec:second-key")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void keyLookupIsCaseSensitive() {
		// 키는 해석하지 않는 불투명 문자열이다. H2 기준 확인이며, MySQL은 V8의 utf8mb4_bin 정의로 같은 동작을 기대한다(미검증).
		records.saveAndFlush(record("project-a", context("project-a", "req-1"), "DEC:ABCDEFGH"));

		assertThat(records.findByProjectIdAndIdempotencyKey("project-a", "dec:abcdefgh")).isEmpty();
	}

	@Test
	void requestIdOf128CharactersIsStoredAndOf129IsRejected() {
		String max = "r".repeat(128);

		assertThat(contexts.capture(key("project-a"), "prod", max, null).context().getRequestId()).hasSize(128);
		assertThatThrownBy(() -> contexts.capture(key("project-a"), "prod", "r".repeat(129), null))
			.isInstanceOf(ApiException.class);
	}
}
