package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.policy.PolicyRequests;
import com.tokenledgercloud.api.domain.policy.PolicyRules;
import com.tokenledgercloud.api.domain.policy.PolicyService;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 판정 흐름 전체(실제 프로젝트 키 인증 → 멱등 조회 → capture → 계산 → 저장)를 H2로 확인한다.
 * 동시 요청은 H2에서도 재현되지만, MySQL의 FK 공유 잠금 교착은 H2로 보이지 않는다. 그래서 바깥 트랜잭션 안에서의
 * 호출 자체를 거부하는 구조를 따로 테스트한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:decision-service;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.rate-unit-tokens=1000000"
})
class DecisionServiceIntegrationTest {

	private static final String PRICED_MODEL = "gpt-priced";

	@Autowired DecisionService service;
	@Autowired PolicyService policies;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository apiKeys;
	@Autowired PricingPlanRepository pricingPlans;
	@Autowired MemberRepository members;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtTokenProvider jwt;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;

	ProjectKeyFixtures fixtures;
	Project projectA;
	Project projectB;
	String keyA;
	String keyB;
	Authentication admin;

	@BeforeEach
	void setUp() {
		clean();
		fixtures = new ProjectKeyFixtures(projects, apiKeys, passwordEncoder);
		projectA = project("alpha");
		projectB = project("beta");
		keyA = fixtures.key(projectA, null, "ACTIVE", null).raw();
		keyB = fixtures.key(projectB, null, "ACTIVE", null).raw();
		members.saveAndFlush(Member.builder().id("admin").email("admin@example.test").name("admin").provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
			"m-admin", ProjectKeyFixtures.ORG, "admin", "ADMIN", LocalDateTime.now(), LocalDateTime.now());
		admin = jwt.getAuthentication(jwt.createToken("admin@example.test", "USER"));
		// 100만 토큰당 prompt 2, completion 8 -> 입력 1000, 출력 500이면 0.006 USD
		pricingPlans.saveAndFlush(PricingPlan.builder().id("plan-1").catalogId("catalog-1").provider("openai").model(PRICED_MODEL)
			.currency("USD").promptRate(new BigDecimal("2")).completionRate(new BigDecimal("8")).version("prices-v1")
			.effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
	}

	@AfterEach
	void clean() {
		for (String table : List.of("decision_records", "policy_request_contexts", "policy_snapshots", "customer_policies",
			"monthly_budget_settings", "pricing_snapshots", "pricing_plans", "organization_members", "project_api_keys",
			"project_environments", "projects", "users")) {
			jdbc.update("delete from " + table);
		}
	}

	private Project project(String name) {
		Project project = fixtures.project(name, ProjectStatus.ACTIVE);
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId(ProjectKeyFixtures.ORG)
			.projectId(project.getId()).environment("prod").build());
		return project;
	}

	private void policy(Project project, String environment, String hard, String requestHard) {
		policies.replace(admin, project.getId(), environment, new PolicyRequests.Replace(0L, new PolicyRules("USD", null,
			new BigDecimal(hard), null, requestHard == null ? null : new BigDecimal(requestHard), true, List.of()), "test policy"));
	}

	private static DecisionCommand command(String requestId, String model, EnforcementMode mode) {
		return new DecisionCommand("2026-10-01", requestId, "prod", "openai", model, mode, 1000, 500,
			new BigDecimal("0.000660"), "sdk-prices", Map.of("feature", "support-summary"));
	}

	private long records() {
		return jdbc.queryForObject("select count(*) from decision_records", Long.class);
	}

	@Test
	void allowsWithinBudgetAndReplaysTheSameResponse() {
		policy(projectA, null, "100", null);

		DecisionResponse first = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));
		DecisionResponse again = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		assertThat(first.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(first.requestId()).isEqualTo("req-1");
		assertThat(first.decisionId()).startsWith("dec-");
		assertThat(first.requiredCostUsd()).isEqualTo("0.006000");
		assertThat(first.remainingMonthlyBudgetUsd()).isEqualTo("100.000000");
		assertThat(first.policies()).singleElement().satisfies(p -> assertThat(p.scope()).isEqualTo("PROJECT"));
		assertThat(first.policyVersion()).startsWith("prj:").doesNotContain("env:");
		assertThat(again).isEqualTo(first);
		assertThat(records()).isEqualTo(1);
	}

	@Test
	void replayKeepsTheFirstDecisionAfterThePolicyChanges() {
		policy(projectA, null, "100", null);
		DecisionResponse first = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		policies.replace(admin, projectA.getId(), null, new PolicyRequests.Replace(1L,
			new PolicyRules("USD", null, new BigDecimal("0.000001"), null, null, true, List.of()), "tighten"));

		assertThat(service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY))).isEqualTo(first);
	}

	@Test
	void sameKeyWithAnotherBodyIsConflict() {
		policy(projectA, null, "100", null);
		service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		assertThatThrownBy(() -> service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ENFORCE)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		assertThat(records()).isEqualTo(1);
	}

	@Test
	void sameRequestIdWithAnotherKeyIsConflict() {
		policy(projectA, null, "100", null);
		service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		assertThatThrownBy(() -> service.decide(keyA, "other-key-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		assertThat(records()).isEqualTo(1);
	}

	@Test
	void sameKeyInAnotherProjectIsAnIndependentDecision() {
		policy(projectA, null, "100", null);
		policy(projectB, null, "100", null);

		DecisionResponse a = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));
		DecisionResponse b = service.decide(keyB, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		assertThat(b.decisionId()).isNotEqualTo(a.decisionId());
		assertThat(records()).isEqualTo(2);
	}

	@Test
	void missingPriceIsIndeterminatePricingNotFound() {
		policy(projectA, null, "100", null);

		DecisionResponse response = service.decide(keyA, "dec:req-1", command("req-1", "model-without-plan", EnforcementMode.ADVISORY));

		assertThat(response.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(response.reasons()).extracting(DecisionResponse.Reason::code).containsExactly("PRICING_NOT_FOUND");
		assertThat(response.requiredCostUsd()).isNull();
	}

	@Test
	void noPolicyIsIndeterminatePolicyNotConfigured() {
		DecisionResponse response = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY));

		assertThat(response.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(response.reasons()).extracting(DecisionResponse.Reason::code).containsExactly("POLICY_NOT_CONFIGURED");
		assertThat(response.policyVersion()).isEqualTo("none");
		assertThat(response.policies()).isEmpty();
	}

	@Test
	void enforceRequestIsJudgedAsAdvisoryWithoutReservation() {
		policy(projectA, null, "100", "0.001");
		policy(projectA, "prod", "100", null);

		DecisionResponse response = service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ENFORCE));

		assertThat(response.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(response.reasons()).extracting(DecisionResponse.Reason::code).containsExactly("REQUEST_COST_LIMIT_EXCEEDED");
		assertThat(response.effectiveMode()).isEqualTo(EnforcementMode.ADVISORY);
		assertThat(response.retryAt()).isNull();
		assertThat(response.policies()).extracting(DecisionResponse.PolicyRef::scope).containsExactly("PROJECT", "ENVIRONMENT");
		assertThat(response.policyVersion()).matches("prj:[^.]+\\.env:[^.]+");
	}

	@Test
	void storedRecordHasNoMetadataOrRequestText() {
		policy(projectA, null, "100", null);
		DecisionCommand withMarker = new DecisionCommand("2026-10-01", "req-1", "prod", "openai", PRICED_MODEL,
			EnforcementMode.ADVISORY, 1000, 500, null, null, Map.of("feature", "secret-marker-7f3a"));

		service.decide(keyA, "dec:req-1", withMarker);

		String stored = jdbc.queryForObject(
			"select concat(cast(evaluation_input_json as varchar), cast(response_json as varchar)) from decision_records", String.class);
		assertThat(stored).doesNotContain("secret-marker-7f3a").doesNotContain("feature");
		assertThat(stored).contains("monthSpendUsd", "estimatedInputTokens");
	}

	@Test
	void rejectsACallInsideAnOuterTransaction() {
		policy(projectA, null, "100", null);
		TransactionTemplate outer = new TransactionTemplate(transactions);

		assertThatThrownBy(() -> outer.executeWithoutResult(status ->
			service.decide(keyA, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("must not run inside a transaction");
		assertThat(records()).isZero();
	}

	@Test
	void concurrentSameKeyAndBodyStoreOneDecisionAndReturnTheSameResponse() throws Exception {
		policy(projectA, null, "100", null);
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<DecisionResponse>> results = pool.invokeAll(List.of(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return service.decide(keyA, "dec:req-race", command("req-race", PRICED_MODEL, EnforcementMode.ADVISORY));
			}, () -> {
				barrier.await(10, TimeUnit.SECONDS);
				return service.decide(keyA, "dec:req-race", command("req-race", PRICED_MODEL, EnforcementMode.ADVISORY));
			}), 30, TimeUnit.SECONDS);

			DecisionResponse first = results.get(0).get();
			DecisionResponse second = results.get(1).get();
			assertThat(second).isEqualTo(first);
			assertThat(records()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void keyRestrictedToAnotherEnvironmentIsForbidden() {
		String devOnly = fixtures.key(projectA, "dev", "ACTIVE", null).raw();

		assertThatThrownBy(() -> service.decide(devOnly, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertThat(records()).isZero();
	}

	@Test
	void keyOfAnInactiveProjectIsForbidden() {
		Project archived = fixtures.project("archived", ProjectStatus.ARCHIVED);
		String archivedKey = fixtures.key(archived, null, "ACTIVE", null).raw();

		assertThatThrownBy(() -> service.decide(archivedKey, "dec:req-1", command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
		assertThat(records()).isZero();
	}

	@Test
	void unknownApiKeyIsUnauthorizedAndStoresNothing() {
		assertThatThrownBy(() -> service.decide("tpk_live_unknown-key-value-000000", "dec:req-1",
			command("req-1", PRICED_MODEL, EnforcementMode.ADVISORY)))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
		assertThat(records()).isZero();
	}
}
