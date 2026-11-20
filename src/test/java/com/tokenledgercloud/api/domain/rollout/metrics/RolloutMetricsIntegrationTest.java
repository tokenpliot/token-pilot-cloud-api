package com.tokenledgercloud.api.domain.rollout.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.decision.DecisionCommand;
import com.tokenledgercloud.api.domain.decision.DecisionOutcome;
import com.tokenledgercloud.api.domain.decision.DecisionResponse;
import com.tokenledgercloud.api.domain.decision.DecisionService;
import com.tokenledgercloud.api.domain.decision.EnforcementMode;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
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
import com.tokenledgercloud.api.domain.rollout.breakglass.BreakGlassService;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 판정 결과·break-glass 지표가 실제 서비스 흐름에서 한 번씩만 세지고, Spring이 만든 레지스트리와
 * /actuator/prometheus에 나오는지 확인한다. (이슈 #19)
 * 같은 JVM의 다른 테스트도 전역 레지스트리에 값을 더하므로, 절대값이 아니라 호출 전후 차이로 확인한다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:rollout-metrics;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.observe.ingestion.rate-unit-tokens=1000000",
		// src/test/resources/application.yml이 main의 application.yml을 가리므로 main과 같은 노출 설정을 여기서 준다.
		"management.endpoints.web.exposure.include=health,prometheus"
})
class RolloutMetricsIntegrationTest {

	private static final String PRICED_MODEL = "gpt-priced";

	@Autowired DecisionService decisions;
	@Autowired BreakGlassService breakGlass;
	@Autowired PolicyService policies;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository apiKeys;
	@Autowired PricingPlanRepository pricingPlans;
	@Autowired MemberRepository members;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtTokenProvider jwt;
	@Autowired JdbcTemplate jdbc;
	@Autowired MeterRegistry registry;
	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;

	Project project;
	String apiKey;
	Authentication admin;

	@BeforeEach
	void setUp() {
		clean();
		ProjectKeyFixtures fixtures = new ProjectKeyFixtures(projects, apiKeys, passwordEncoder);
		project = fixtures.project("alpha", ProjectStatus.ACTIVE);
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId(ProjectKeyFixtures.ORG)
				.projectId(project.getId()).environment("prod").build());
		apiKey = fixtures.key(project, null, "ACTIVE", null).raw();
		members.saveAndFlush(Member.builder().id("admin").email("admin@example.test").name("admin")
				.provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				"m-admin", ProjectKeyFixtures.ORG, "admin", "ADMIN", LocalDateTime.now(), LocalDateTime.now());
		admin = jwt.getAuthentication(jwt.createToken("admin@example.test", "USER"));
		pricingPlans.saveAndFlush(PricingPlan.builder().id("plan-1").catalogId("catalog-1").provider("openai")
				.model(PRICED_MODEL).currency("USD").promptRate(new BigDecimal("2")).completionRate(new BigDecimal("8"))
				.version("prices-v1").effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
	}

	// 판정 서비스는 단계마다 자기 트랜잭션에서 커밋하므로 직접 정리한다.
	@AfterEach
	void clean() {
		for (String table : List.of("operational_events", "break_glass_grants", "decision_records",
				"policy_request_contexts", "policy_snapshots", "customer_policies", "monthly_budget_settings",
				"pricing_snapshots", "pricing_plans", "organization_members", "project_api_keys",
				"project_environments", "projects", "users")) {
			jdbc.update("delete from " + table);
		}
	}

	private void policy(long expectedVersion, String hardLimit) {
		policies.replace(admin, project.getId(), null, new PolicyRequests.Replace(expectedVersion, new PolicyRules("USD",
				null, new BigDecimal(hardLimit), null, null, true, List.of()), "test policy"));
	}

	private DecisionResponse decide(String requestId, String model) {
		return decisions.decide(apiKey, "dec:" + requestId, new DecisionCommand("2026-10-01", requestId, "prod",
				"openai", model, EnforcementMode.ADVISORY, 1000, 500, new BigDecimal("0.000660"), "sdk-prices",
				Map.of()));
	}

	private double count(String name, String tagKey, String tagValue) {
		Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
		return counter == null ? 0 : counter.count();
	}

	private double outcomes(DecisionOutcome outcome) {
		return count(RolloutMetrics.DECISION_OUTCOME, "outcome", outcome.name());
	}

	@Test
	void eachNewDecisionIsCountedOnceByOutcomeAndReplayIsNot() {
		policy(0, "100");
		double allowBefore = outcomes(DecisionOutcome.ALLOW);
		double indeterminateBefore = outcomes(DecisionOutcome.INDETERMINATE);

		assertThat(decide("req-1", PRICED_MODEL).outcome()).isEqualTo(DecisionOutcome.ALLOW);
		// 같은 멱등 키로 다시 보내면 처음 응답을 돌려줄 뿐 새 판정이 아니다.
		decide("req-1", PRICED_MODEL);
		// 가격이 없는 모델은 판정 불가다. 정책 거부와 다른 값으로 세진다.
		assertThat(decide("req-2", "gpt-unpriced").outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);

		assertThat(outcomes(DecisionOutcome.ALLOW) - allowBefore).isEqualTo(1);
		assertThat(outcomes(DecisionOutcome.INDETERMINATE) - indeterminateBefore).isEqualTo(1);
	}

	@Test
	void budgetDenialIsCountedUnderItsOwnOutcome() {
		policy(0, "0.000001");
		double denyBudgetBefore = outcomes(DecisionOutcome.DENY_BUDGET);
		double allowBefore = outcomes(DecisionOutcome.ALLOW);

		// 한도(0.000001)보다 비싼 호출(0.006)은 예산 거부다.
		assertThat(decide("req-deny", PRICED_MODEL).outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);

		assertThat(outcomes(DecisionOutcome.DENY_BUDGET) - denyBudgetBefore).isEqualTo(1);
		assertThat(outcomes(DecisionOutcome.ALLOW) - allowBefore).isZero();
	}

	@Test
	void breakGlassIssueAndRevokeAreCounted() {
		double issuedBefore = count(RolloutMetrics.BREAK_GLASS, "action", "ISSUED");
		double revokedBefore = count(RolloutMetrics.BREAK_GLASS, "action", "REVOKED");
		LocalDateTime now = LocalDateTime.of(2026, 11, 10, 9, 0);

		String grantId = breakGlass.issue(admin,
				new BreakGlassIssueRequest(project.getId(), "prod", "provider 장애 대응", 30), now).grantId();
		breakGlass.revoke(admin, grantId, new BreakGlassRevokeRequest("복구"), now.plusMinutes(5));

		assertThat(count(RolloutMetrics.BREAK_GLASS, "action", "ISSUED") - issuedBefore).isEqualTo(1);
		assertThat(count(RolloutMetrics.BREAK_GLASS, "action", "REVOKED") - revokedBefore).isEqualTo(1);
	}

	@Test
	void countersAreExposedOnPrometheusEndpoint() throws Exception {
		policy(0, "100");
		decide("req-prom", PRICED_MODEL);
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();

		String body = mvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("tokenpilot_decision_outcome_total{outcome=\"ALLOW\"");
	}
}
