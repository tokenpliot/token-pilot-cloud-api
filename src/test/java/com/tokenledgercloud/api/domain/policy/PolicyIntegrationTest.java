package com.tokenledgercloud.api.domain.policy;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.accounting.snapshot.*;
import com.tokenledgercloud.api.domain.budget.dto.BudgetUpsertRequest;
import com.tokenledgercloud.api.domain.budget.entity.MonthlyBudgetSetting;
import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;
import com.tokenledgercloud.api.domain.budget.service.BudgetService;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.member.entity.*;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.project.entity.*;
import com.tokenledgercloud.api.domain.project.repository.*;
import com.tokenledgercloud.api.global.exception.*;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/** Real HTTP authentication, tenant checks, transactions and repositories. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:policy-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.flyway.enabled=false"
})
class PolicyIntegrationTest {
	@Autowired PolicyService policies;
	@Autowired PolicyContextService contexts;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired MonthlyBudgetSettingRepository budgets;
	@Autowired MemberRepository members;
	@Autowired PricingSnapshotRepository prices;
	@Autowired BudgetService legacy;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;
	@Autowired WebApplicationContext web;
	@Autowired FilterChainProxy filters;
	MockMvc mvc;
	Authentication admin;
	String token;

	@BeforeEach
	void setUp() {
		for (String table : List.of("policy_request_contexts", "policy_snapshots", "customer_policies", "monthly_budget_settings",
			"pricing_snapshots", "organization_members", "project_environments", "projects", "users")) jdbc.update("delete from " + table);
		project("project-a", "default-org", "alpha");
		project("project-b", "org-b", "beta");
		member("admin", "default-org", "ADMIN");
		member("viewer", "default-org", "VIEWER");
		member("outsider", "org-b", "ADMIN");
		token = jwt.createToken("admin@example.test", "USER");
		admin = jwt.getAuthentication(token);
		mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(filters).build();
	}

	@Test
	void importsLegacyWithoutInventingSoftLimitsAndPreservesHistoryAcrossReplaceAndRollback() {
		budgets.saveAndFlush(MonthlyBudgetSetting.builder().organizationId("default-org").projectId("project-a")
			.environment("prod").limitUsd(new BigDecimal("100.000000")).threshold50Enabled(false)
			.threshold80Enabled(true).threshold100Enabled(true).build());
		var first = policies.importBudget(admin, "project-a", "prod", new PolicyRequests.ImportBudget("Reviewed legacy budget"));
		assertThat(first.rules().monthlySoftLimitUsd()).isNull();
		assertThat(first.rules().notificationThresholds()).containsExactly(80, 100);
		assertThat(first.actorId()).isEqualTo("admin");
		assertThat(first.changeType()).isEqualTo("IMPORT_LEGACY");
		var second = policies.replace(admin, "project-a", "prod", replace(1, "25", false));
		var restored = policies.rollback(admin, "project-a", "prod", new PolicyRequests.Rollback(2L, 1L, "Restore approved policy"));
		assertThat(restored.revision()).isEqualTo(3);
		assertThat(restored.policyId()).isEqualTo(first.policyId());
		assertThat(restored.restoredFromVersion()).isEqualTo(1);
		assertThat(restored.rules()).isEqualTo(first.rules());
		assertThat(second.rules().active()).isFalse();
		assertThat(policies.version(admin, "project-a", "prod", 1)).usingRecursiveComparison()
			.ignoringFields("snapshotJson").isEqualTo(first); // MySQL normalizes JSON whitespace/key order.
		assertThat(policies.history(admin, "project-a", "prod", Long.MAX_VALUE, 2)).extracting(PolicyVersionView::revision).containsExactly(3L, 2L);
		assertThat(policies.history(admin, "project-a", "prod", 2, 2)).extracting(PolicyVersionView::revision).containsExactly(1L);
		assertThat(budgets.findAll()).singleElement().satisfies(b -> {
			assertThat(b.getLimitUsd()).isEqualByComparingTo("100");
			assertThat(b.getPolicyVersion()).isEqualTo(restored.version());
			assertThat(b.getUpdatedById()).isEqualTo("admin");
		});
	}

	@Test
	void pinnedRequestRetainsBothPoliciesAndOldPriceAfterChangesAndRetries() {
		var projectPolicy = policies.replace(admin, "project-a", null, replace(0, "100", true));
		var envPolicy = policies.replace(admin, "project-a", "prod", replace(0, "30", true));
		PricingSnapshot price1 = price("price-v1", "2");
		var first = contexts.capture(key("prod"), "prod", "request-1", price1.getId());
		assertThat(first.policies()).extracting(PolicyVersionView::snapshotId).containsExactly(projectPolicy.snapshotId(), envPolicy.snapshotId());
		policies.replace(admin, "project-a", null, replace(1, "80", true));
		policies.replace(admin, "project-a", "prod", replace(1, "10", false));
		PricingSnapshot price2 = price("price-v2", "5");
		var retried = contexts.capture(key("prod"), "prod", "request-1", price2.getId());
		assertThat(retried.context().getId()).isEqualTo(first.context().getId());
		assertThat(retried.price().version()).isEqualTo("price-v1");
		assertThat(retried.policies()).isEqualTo(first.policies());
		var next = contexts.capture(key("prod"), "prod", "request-2", null);
		assertThat(next.policies()).extracting(PolicyVersionView::revision).containsExactly(2L, 2L);
		assertThat(next.policies().getLast().rules().active()).isFalse();
		assertThat(next.priceStatus()).isEqualTo("UNAVAILABLE");
		assertThat(next.price()).isNull();
		assertThat(contexts.explain(admin, "project-a", "prod", "request-1").price()).isEqualTo(first.price());
		assertThatThrownBy(() -> contexts.capture(key("prod"), "dev", "bad", price1.getId()))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
	}

	@Test
	void decimalPrecisionSurvivesDatabaseJsonStorageAndReload() {
		String exact = "999999999999.999999";
		policies.replace(admin, "project-a", "prod", replace(0, exact, true));
		assertThat(policies.current(admin, "project-a", "prod").rules().monthlyHardLimitUsd()).isEqualByComparingTo(exact);
		assertThat(budgets.findAll()).singleElement().satisfies(b -> assertThat(b.getLimitUsd()).isEqualByComparingTo(exact));
	}

	@Test
	void twoConcurrentUpdatesCannotLoseARevision() throws Exception {
		policies.replace(admin, "project-a", "prod", replace(0, "100", true));
		concurrentWrites(1);
		assertThat(policies.current(admin, "project-a", "prod").revision()).isEqualTo(2);
		assertThat(policies.history(admin, "project-a", "prod", Long.MAX_VALUE, 100)).hasSize(2);
	}

	@Test
	void twoConcurrentFirstWritesCannotCreateDuplicateScope() throws Exception {
		concurrentWrites(0);
		assertThat(jdbc.queryForObject("select count(*) from customer_policies", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(1);
	}

	@Test
	void anonymousProjectKeyViewerAndForeignMemberCannotChangePolicies() throws Exception {
		mvc.perform(put(path()).contentType(MediaType.APPLICATION_JSON).content(body(0))).andExpect(status().isUnauthorized());
		mvc.perform(put(path()).header("X-API-Key", "project-key-is-not-a-member-key").contentType(MediaType.APPLICATION_JSON)
			.content(body(0))).andExpect(status().isUnauthorized());
		mvc.perform(put(path()).header("Authorization", bearer("viewer")).contentType(MediaType.APPLICATION_JSON)
			.content(body(0))).andExpect(status().isForbidden());
		mvc.perform(put(path()).header("Authorization", bearer("outsider")).contentType(MediaType.APPLICATION_JSON)
			.content(body(0))).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isZero();
	}

	@Test
	void httpActorIsAuthenticatedMemberAndForeignReadsAreHidden() throws Exception {
		mvc.perform(put(path()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(0)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.actorId").value("admin"))
			.andExpect(jsonPath("$.data.revision").value(1));
		mvc.perform(get(path()).header("Authorization", bearer("viewer"))).andExpect(status().isOk());
		for (String suffix : List.of("", "/versions", "/versions/1")) {
			mvc.perform(get("/api/policies/projects/project-a" + suffix).param("environment", "prod")
				.header("Authorization", bearer("outsider"))).andExpect(status().isNotFound());
		}
		mvc.perform(post("/api/policies/projects/project-a/rollback").param("environment", "prod")
			.header("Authorization", bearer("outsider")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":1,\"restoreVersion\":1,\"reason\":\"foreign\"}"))
			.andExpect(status().isNotFound());
		contexts.capture(key("prod"), "prod", "req", null);
		mvc.perform(get("/api/policies/projects/project-a/request-context").param("environment", "prod").param("requestId", "req")
			.header("Authorization", bearer("outsider"))).andExpect(status().isNotFound());
	}

	@Test
	void invalidAmountsEnvironmentAndStaleVersionsReturnClientErrorsWithoutWrites() throws Exception {
		for (String malformed : List.of("{", body(0).replace("100", "-1"), body(0).replace("100", "1000000000000"),
			body(0).replace("100", "0.0000001"), body(0).replace("USD", "KRW"),
			body(0).replace("\"monthlySoftLimitUsd\":80", "\"monthlySoftLimitUsd\":101"))) {
			mvc.perform(put(path()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(malformed))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(put("/api/policies/projects/project-a?environment=missing").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(body(0))).andExpect(status().isBadRequest());
		policies.replace(admin, "project-a", "prod", replace(0, "100", true));
		mvc.perform(put(path()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(0)))
			.andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(1);
	}

	@Test
	void impactIsReadOnlyAndProjectPolicyCoversEveryEnvironmentWithoutReplacingTheirPolicies() {
		policies.replace(admin, "project-a", "prod", replace(0, "10", true));
		var impact = policies.preview(admin, "project-a", null, replace(0, "100", true));
		assertThat(impact.affectedRegisteredEnvironments()).containsExactly("dev", "prod");
		assertThat(impact.includesFutureEnvironments()).isTrue();
		assertThat(impact.estimateType()).isEqualTo("STRUCTURAL_ONLY");
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(1);
	}

	@Test
	void legacyWritesRemainCompatibleUntilScopeIsManagedAndCannotBypassAuditAfterImport() {
		var input = new BudgetUpsertRequest("project-a", "prod", "MONTHLY", new BigDecimal("100"), List.of(80));
		legacy.upsert(input);
		policies.importBudget(admin, "project-a", "prod", new PolicyRequests.ImportBudget("Migrate"));
		assertThatThrownBy(() -> legacy.upsert(input)).isInstanceOfSatisfying(ApiException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
		assertThat(policies.current(admin, "project-a", "prod").revision()).isEqualTo(1);
	}

	@Test
	void ambiguousLegacyRowsAbortEntireChange() {
		for (int i = 0; i < 2; i++) budgets.saveAndFlush(MonthlyBudgetSetting.builder().organizationId("default-org")
			.projectId("project-a").environment("prod").limitUsd(BigDecimal.TEN).build());
		assertThatThrownBy(() -> policies.importBudget(admin, "project-a", "prod", new PolicyRequests.ImportBudget("Migrate")))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
		assertThat(jdbc.queryForObject("select count(*) from customer_policies", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isZero();
	}

	private void concurrentWrites(long expected) throws Exception {
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Void> task = () -> {
				start.await();
				try {
					policies.replace(admin, "project-a", "prod", replace(expected, "50", true));
					successes.incrementAndGet();
				} catch (ApiException e) {
					if (e.getErrorCode() != ErrorCode.CONFLICT) throw e;
					conflicts.incrementAndGet();
				}
				return null;
			};
			var a = executor.submit(task);
			var b = executor.submit(task);
			start.countDown();
			a.get(15, TimeUnit.SECONDS);
			b.get(15, TimeUnit.SECONDS);
		}
		assertThat(successes).hasValue(1);
		assertThat(conflicts).hasValue(1);
	}

	private void project(String id, String org, String name) {
		projects.saveAndFlush(Project.builder().id(id).organizationId(org).projectKey(name).name(name).status(ProjectStatus.ACTIVE).build());
		for (String env : List.of("prod", "dev")) environments.saveAndFlush(ProjectEnvironment.builder().organizationId(org).projectId(id).environment(env).build());
	}

	private void member(String id, String org, String role) {
		members.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id).provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
			id, org, id, role, LocalDateTime.now(), LocalDateTime.now());
	}

	private PricingSnapshot price(String version, String rate) {
		PricingPlan plan = PricingPlan.builder().id("plan-" + version).provider("test").model("test-model").currency("USD")
			.promptRate(new BigDecimal(rate)).completionRate(new BigDecimal(rate)).version(version)
			.effectiveFrom(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
		return prices.saveAndFlush(PricingSnapshot.fromPlan(plan, 1000000, "SYSTEM", "test-price-publisher"));
	}

	private AuthenticatedProjectApiKey key(String env) { return new AuthenticatedProjectApiKey("default-org", "project-a", "key-a", env); }
	private PolicyRequests.Replace replace(long expected, String hard, boolean active) {
		return new PolicyRequests.Replace(expected, new PolicyRules("USD", null, new BigDecimal(hard),
			new BigDecimal("0.5"), BigDecimal.ONE, active, List.of(80, 100)), "Approved change");
	}
	private String bearer(String member) { return "Bearer " + jwt.createToken(member + "@example.test", "USER"); }
	private String path() { return "/api/policies/projects/project-a?environment=prod"; }
	private String body(long expected) {
		return """
			{"expectedVersion":%d,"rules":{"currency":"USD","monthlySoftLimitUsd":80,"monthlyHardLimitUsd":100,
			"requestSoftLimitUsd":0.5,"requestHardLimitUsd":1,"active":true,"notificationThresholds":[80]},"reason":"Approved"}
			""".formatted(expected);
	}
}
