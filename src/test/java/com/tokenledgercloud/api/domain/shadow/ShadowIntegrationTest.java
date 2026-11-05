package com.tokenledgercloud.api.domain.shadow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.tokenledgercloud.api.domain.accounting.*;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.accounting.snapshot.*;
import com.tokenledgercloud.api.domain.decision.*;
import com.tokenledgercloud.api.domain.member.entity.*;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.policy.*;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.project.entity.*;
import com.tokenledgercloud.api.domain.project.repository.*;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;
import com.tokenledgercloud.api.domain.shadow.service.*;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.*;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:shadow-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.flyway.enabled=false"
})
class ShadowIntegrationTest {
    @Autowired ShadowService shadow;
    @Autowired PolicyService policies;
    @Autowired ProjectRepository projects;
    @Autowired ProjectEnvironmentRepository environments;
    @Autowired MemberRepository members;
    @Autowired UsageLogRepository usage;
    @Autowired UsageAccountingValueRepository values;
    @Autowired PricingSnapshotRepository prices;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired WebApplicationContext web;
    @Autowired FilterChainProxy security;
    MockMvc mvc;
    Authentication admin;
    Authentication outsider;
    PricingSnapshot price;
    PolicyVersionView baseline;

    @BeforeEach
    void setUp() {
        cleanup();
        project("shadow-a", "shadow-org-a");
        project("shadow-b", "shadow-org-b");
        member("shadow-admin", "shadow-org-a", "ADMIN");
        member("shadow-viewer", "shadow-org-a", "VIEWER");
        member("shadow-outsider", "shadow-org-b", "ADMIN");
        admin = auth("shadow-admin");
        outsider = auth("shadow-outsider");
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(security).build();
        baseline = policies.replace(admin, "shadow-a", "prod", new PolicyRequests.Replace(0L, rules("100", "50", true), "Baseline"));
        price = prices.saveAndFlush(PricingSnapshot.fromPlan(PricingPlan.builder().id("shadow-plan")
            .provider("test").model("model").currency("USD").promptRate(BigDecimal.ONE).completionRate(BigDecimal.ONE)
            .version("price-1").effectiveFrom(LocalDateTime.of(2026, 11, 1, 0, 0)).build(), 1000000, "SYSTEM", "test"));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from shadow_audits where kind <> 'RUN'");
        jdbc.update("delete from shadow_audits");
        for (String table : List.of("policy_request_contexts", "usage_accounting_values", "usage_events",
            "policy_snapshots", "customer_policies", "monthly_budget_settings", "pricing_snapshots",
            "organization_members", "project_environments", "projects", "users")) jdbc.update("delete from " + table);
    }

    @Test
    void httpReplayReportsDenialsReasonsAndCandidatesWithoutActivatingPolicy() throws Exception {
        event("one", "prod", 1, "2");
        event("two", "prod", 2, "8");
        event("three", "prod", 3, "1");
        mvc.perform(post(path() + "/runs").header("Authorization", bearer("shadow-admin"))
            .header("Idempotency-Key", "http-replay-1").contentType(MediaType.APPLICATION_JSON)
            .content(ShadowJson.write(request("100", "5"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.candidateDenied").value(1))
            .andExpect(jsonPath("$.data.summary.candidateEvaluated").value(3))
            .andExpect(jsonPath("$.data.summary.candidateDenialRate").value(0.333333))
            .andExpect(jsonPath("$.data.summary.falseDenialCandidates").value(1))
            .andExpect(jsonPath("$.data.rolloutReadiness").value("MANUAL_REVIEW_REQUIRED"));
        var run = shadow.runs(admin, "shadow-a", 0, 10).getFirst();
        var changed = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, true, true);
        assertThat(changed.items()).singleElement().satisfies(d -> {
            assertThat(d.sample().usageEventId()).isEqualTo("two");
            assertThat(d.after().findings()).extracting(Finding::code).contains("REQUEST_HARD_LIMIT");
            assertThat(d.sample().pricingSnapshotId()).isEqualTo(price.getId());
            assertThat(d.sample().pricingVersion()).isEqualTo("price-1");
        });
        assertThat(policies.current(admin, "shadow-a", "prod").snapshotId()).isEqualTo(baseline.snapshotId());
        assertThat(jdbc.queryForObject("select limit_usd from monthly_budget_settings where project_id='shadow-a'", BigDecimal.class))
            .isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(1);
    }

    @Test
    void snapshotsAndInputsRemainFrozenAfterPoliciesAndLedgerRevisionsChange() {
        var u = event("frozen", "prod", 1, "8");
        var request = request("100", "5");
        var first = shadow.run(admin, "shadow-a", "freeze-key", request);
        policies.replace(admin, "shadow-a", "prod", new PolicyRequests.Replace(1L, rules("1", "1", true), "Changed"));
        actual(u, 2, "1");
        var retried = shadow.run(admin, "shadow-a", "freeze-key", request);
        assertThat(retried).isEqualTo(first);
        var decision = shadow.decisions(admin, "shadow-a", first.runId(), 0, 10, false, false).items().getFirst();
        assertThat(decision.sample().costUsd()).isEqualByComparingTo("8");
        assertThat(decision.sample().accountingRevision()).isEqualTo(1);
        assertThat(first.baselinePolicies()).extracting(PolicyBinding::version).containsExactly(baseline.version());
        var later = shadow.run(admin, "shadow-a", "later-key", request);
        assertThat(later.summary().candidateDenied()).isZero();
        assertThat(later.summary().sampled()).isEqualTo(1);
    }

    @Test
    void unknownActualCostsAndLaterMonthlyBalancesAreNeverTreatedAsFree() {
        var estimated = event("estimated", "prod", 1, null);
        values.saveAndFlush(UsageAccountingValue.of(estimated.getId(), 1,
            new AccountingAmount(AccountingAmount.Kind.ESTIMATED, AccountingAmount.PricingStatus.PRICED,
                BigDecimal.ONE, "USD", AccountingAmount.Source.SERVER_CATALOG),
            price.getId(), null, "TEST", "SYSTEM", "test"));
        event("after-unknown", "prod", 2, "2");
        event("provable-denial", "prod", 3, "8");
        var run = shadow.run(admin, "shadow-a", "unknown-key", request("100", "5"));
        assertThat(run.summary().indeterminate()).isEqualTo(2);
        assertThat(run.summary().candidateEvaluated()).isEqualTo(1);
        assertThat(run.summary().candidateDenialRate()).isEqualByComparingTo("1");
        var rows = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items();
        assertThat(rows.getFirst().sample().costUsd()).isNull();
        assertThat(rows.get(1).after().findings()).extracting(Finding::code).contains("MONTHLY_SPEND_INCOMPLETE");
        assertThat(rows.get(2).after().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
    }

    @Test
    void projectBudgetIncludesOtherEnvironmentsAndInactiveCandidateCannotWeakenIt() {
        policies.replace(admin, "shadow-a", null, new PolicyRequests.Replace(0L, rules("95", null, true), "Project"));
        event("other-env", "dev", 1, "90");
        event("target", "prod", 2, "10");
        var run = shadow.run(admin, "shadow-a", "project-scope", new RunRequest(baseline.snapshotId(), "2026-11",
            rules("1000", null, false), "Disable environment only"));
        assertThat(run.summary().sampled()).isEqualTo(1);
        var row = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.sample().projectSpendBeforeUsd()).isEqualByComparingTo("90");
        assertThat(row.sample().environmentSpendBeforeUsd()).isEqualByComparingTo("0");
        assertThat(row.after().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(row.after().findings()).extracting(Finding::code).contains("MONTHLY_HARD_LIMIT");
    }

    @Test
    void softLimitsWarnWithoutDenialAndEqualHardBoundaryRemainsAllowed() {
        event("boundary", "prod", 1, "5");
        var candidate = new PolicyRules("USD", new BigDecimal("4"), new BigDecimal("5"),
            new BigDecimal("4"), new BigDecimal("5"), true, List.of(80));
        var run = shadow.run(admin, "shadow-a", "boundary-key",
            new RunRequest(baseline.snapshotId(), "2026-11", candidate, "Boundary"));
        var row = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.after().outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(row.after().findings()).extracting(Finding::code).containsExactly("MONTHLY_SOFT_LIMIT", "REQUEST_SOFT_LIMIT");
    }

    @Test
    void blockedCallsWithZeroCostAreNotEvidenceOfPrecallAffordability() {
        var u = event("blocked", "prod", 1, "0");
        u.setCallStatus("BLOCKED_BY_CLIENT");
        usage.saveAndFlush(u);
        var run = shadow.run(admin, "shadow-a", "blocked-key", request("100", "5"));
        assertThat(run.summary().indeterminate()).isEqualTo(1);
        assertThat(run.summary().candidateDenialRate()).isNull();
        assertThat(run.rolloutReadiness()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void tenantAndRoleChecksProtectWritesReadsAndForeignSnapshotReferences() throws Exception {
        var run = shadow.run(admin, "shadow-a", "tenant-key", request("100", "5"));
        var content = ShadowJson.write(request("100", "5"));
        mvc.perform(post(path() + "/runs").header("Idempotency-Key", "anonymous-key")
            .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isUnauthorized());
        mvc.perform(post(path() + "/runs").header("X-API-Key", "project-key-is-not-member-key")
            .header("Idempotency-Key", "project-key-request").contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isUnauthorized());
        for (String user : List.of("shadow-viewer", "shadow-outsider")) {
            mvc.perform(post(path() + "/runs").header("Authorization", bearer(user))
                .header("Idempotency-Key", "forbidden-key").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().is(user.equals("shadow-viewer") ? 403 : 404));
        }
        mvc.perform(get(path() + "/runs/" + run.runId()).header("Authorization", bearer("shadow-viewer")))
            .andExpect(status().isOk());
        for (String suffix : List.of("", "/decisions", "/execution-events")) {
            mvc.perform(get(path() + "/runs/" + run.runId() + suffix).header("Authorization", bearer("shadow-outsider")))
                .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/shadow/projects/shadow-b/runs").header("Authorization", bearer("shadow-outsider"))
            .header("Idempotency-Key", "foreign-snapshot").contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyIsPerProjectAndConflictingInputDoesNotAppend() throws Exception {
        var first = shadow.run(admin, "shadow-a", "shared-key", request("100", "5"));
        assertThat(shadow.run(admin, "shadow-a", "shared-key", request("100", "5")).runId()).isEqualTo(first.runId());
        assertThatThrownBy(() -> shadow.run(admin, "shadow-a", "shared-key", request("100", "6")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        var foreignBase = policies.replace(outsider, "shadow-b", "prod",
            new PolicyRequests.Replace(0L, rules("100", "50", true), "Other"));
        assertThat(shadow.run(outsider, "shadow-b", "shared-key",
            new RunRequest(foreignBase.snapshotId(), "2026-11", rules("100", "5", true), "Compare")).runId()).isNotEqualTo(first.runId());
        assertThat(jdbc.queryForObject("select count(*) from shadow_audits where kind='RUN'", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentRetriesCreateOnlyOneRun() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> call = () -> { start.await(); return shadow.run(admin, "shadow-a", "concurrent-key", request("100", "5")).runId(); };
            var a = executor.submit(call);
            var b = executor.submit(call);
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from shadow_audits", Integer.class)).isEqualTo(1);
    }

    @Test
    void customerOutageOverrideAndPolicyDenialAreSeparateAppendOnlyAuditFacts() throws Exception {
        event("feedback", "prod", 1, "8");
        var run = shadow.run(admin, "shadow-a", "feedback-run", request("100", "5"));
        var outage = new FeedbackRequest("feedback", ActualExecution.PROCEEDED, CustomerOverride.ALLOW,
            DecisionOutcome.CONTROL_PLANE_UNAVAILABLE, EnforcementMode.ADVISORY, "Customer proceeded during outage");
        var first = shadow.feedback(admin, "shadow-a", run.runId(), "outage-event", outage);
        assertThat(first.categories()).containsExactly("SERVER_UNAVAILABLE", "CUSTOMER_OVERRIDE");
        assertThat(first.shadowOutcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(first.evidenceSource()).isEqualTo("CUSTOMER_REPORTED_BY_MEMBER");
        assertThat(shadow.feedback(admin, "shadow-a", run.runId(), "outage-event", outage)).isEqualTo(first);
        var blocked = new FeedbackRequest("feedback", ActualExecution.BLOCKED, CustomerOverride.NONE,
            DecisionOutcome.DENY_BUDGET, EnforcementMode.ENFORCE, "Separate reported attempt");
        mvc.perform(post(path() + "/runs/" + run.runId() + "/execution-events").header("Authorization", bearer("shadow-admin"))
            .header("Idempotency-Key", "blocked-event").contentType(MediaType.APPLICATION_JSON).content(ShadowJson.write(blocked)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.categories[0]").value("POLICY_DENIAL"));
        var report = shadow.report(admin, "shadow-a", run.runId());
        assertThat(report.customerFeedbackEvents()).isEqualTo(2);
        assertThat(report.serverUnavailableEvents()).isEqualTo(1);
        assertThat(report.customerOverrideEvents()).isEqualTo(1);
        assertThat(report.summary()).isEqualTo(run.summary());
        assertThat(shadow.feedback(admin, "shadow-a", run.runId(), "POLICY_DENIAL", 0, 20)).hasSize(1);
        assertThat(shadow.feedback(admin, "shadow-a", run.runId(), "SERVER_UNAVAILABLE", 0, 20)).containsExactly(first);
        assertThatThrownBy(() -> shadow.feedback(admin, "shadow-a", run.runId(), "outage-event", blocked))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void rolloutRecordsScopeOptInAndRollbackWithoutChangingPolicyOrClientSettings() {
        var run = shadow.run(admin, "shadow-a", "rollout-run", request("100", "5"));
        var enforce = new EnforcementSettings(EnforcementMode.ENFORCE, Set.of(DecisionOutcome.DENY_BUDGET),
            FailureBehavior.FAIL_OPEN, FailureBehavior.FAIL_OPEN);
        assertThatThrownBy(() -> shadow.rollout(admin, "shadow-a", "missing-optin",
            new RolloutRequest(0, "prod", enforce, false, run.runId(), "Missing consent"))).isInstanceOf(ApiException.class);
        var request = new RolloutRequest(0, "prod", enforce, true, run.runId(), "Customer declared rollout");
        var first = shadow.rollout(admin, "shadow-a", "rollout-event", request);
        assertThat(first.before()).isNull();
        assertThat(first.beforeOptIn()).isNull();
        assertThat(first.afterOptIn()).isTrue();
        assertThat(first.evidenceSource()).isEqualTo("CUSTOMER_DECLARATION_ONLY");
        assertThat(shadow.rollout(admin, "shadow-a", "rollout-event", request)).isEqualTo(first);
        var rollback = shadow.rollout(admin, "shadow-a", "rollback-event",
            new RolloutRequest(1, "prod", EnforcementSettings.defaults(), false, run.runId(), "Customer reverted"));
        assertThat(rollback.revision()).isEqualTo(2);
        assertThat(rollback.before()).isEqualTo(enforce);
        assertThat(rollback.after().mode()).isEqualTo(EnforcementMode.OBSERVE);
        assertThat(shadow.rollouts(admin, "shadow-a", "prod", 0, 20)).containsExactly(rollback, first);
        assertThatThrownBy(() -> shadow.rollout(admin, "shadow-a", "stale-rollout", request))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(policies.current(admin, "shadow-a", "prod").revision()).isEqualTo(1);
    }

    @Test
    void invalidRequestsReturn400AndDoNotWriteAudit() throws Exception {
        var body = ShadowJson.write(request("100", "5"));
        mvc.perform(post(path() + "/runs").header("Authorization", bearer("shadow-admin"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{", body.replace("2026-11", "2026-13"),
            body.replace("\"requestHardLimitUsd\":\"5.000000\"", "\"requestHardLimitUsd\":\"-1\""))) {
            mvc.perform(post(path() + "/runs").header("Authorization", bearer("shadow-admin"))
                .header("Idempotency-Key", "invalid-key").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path() + "/runs").header("Authorization", bearer("shadow-admin")).param("size", "0"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from shadow_audits", Integer.class)).isZero();
    }

    @Test
    void utcMonthBoundariesAndOtherProjectsAreExcluded() {
        var previous = event("previous-month", "prod", 1, "80");
        previous.setOccurredAt(LocalDateTime.of(2026, 10, 31, 23, 59));
        usage.saveAndFlush(previous);
        var next = event("next-month", "prod", 2, "80");
        next.setOccurredAt(LocalDateTime.of(2026, 12, 1, 0, 0));
        usage.saveAndFlush(next);
        var foreign = event("foreign", "prod", 3, "80");
        foreign.setProjectId("shadow-b");
        foreign.setOrganizationId("shadow-org-b");
        usage.saveAndFlush(foreign);
        event("in-month", "prod", 4, "2");
        var run = shadow.run(admin, "shadow-a", "month-boundaries", request("5", "5"));
        assertThat(run.summary().sampled()).isEqualTo(1);
        assertThat(run.summary().candidateDenied()).isZero();
    }

    private UsageLog event(String id, String env, int minute, String amount) {
        UsageLog row = usage.saveAndFlush(UsageLog.builder().id(id).organizationId("shadow-org-a").projectId("shadow-a")
            .environment(env).requestId("request-" + id).provider("test").model("model").pricingVersion("price-1")
            .accountingStatus("VERIFIED").callStatus("SUCCEEDED").settlementState("UNSETTLED").tokenSemantics("INCLUSIVE_V1")
            .schemaVersion("observe-v1").occurredAt(LocalDateTime.of(2026, 11, 1, 0, minute)).build());
        if (amount != null) actual(row, 1, amount);
        return row;
    }
    private void actual(UsageLog event, int revision, String amount) {
        values.saveAndFlush(UsageAccountingValue.of(event.getId(), revision,
            new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED, new BigDecimal(amount),
                "USD", AccountingAmount.Source.SERVER_CATALOG), price.getId(), null, "TEST", "SYSTEM", "test"));
    }
    private void project(String id, String org) {
        projects.saveAndFlush(Project.builder().id(id).organizationId(org).projectKey(id).name(id).status(ProjectStatus.ACTIVE).build());
        for (String env : List.of("prod", "dev")) environments.saveAndFlush(
            ProjectEnvironment.builder().organizationId(org).projectId(id).environment(env).build());
    }
    private void member(String id, String org, String role) {
        members.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id).provider("local").role(Role.USER).build());
        jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
            id, org, id, role, LocalDateTime.now(), LocalDateTime.now());
    }
    private RunRequest request(String monthly, String perRequest) {
        return new RunRequest(baseline.snapshotId(), "2026-11", rules(monthly, perRequest, true), "Compare");
    }
    private static PolicyRules rules(String monthly, String perRequest, boolean active) {
        return new PolicyRules("USD", null, new BigDecimal(monthly), null,
            perRequest == null ? null : new BigDecimal(perRequest), active, List.of(80));
    }
    private Authentication auth(String member) { return jwt.getAuthentication(jwt.createToken(member + "@example.test", "USER")); }
    private String bearer(String member) { return "Bearer " + jwt.createToken(member + "@example.test", "USER"); }
    private String path() { return "/api/shadow/projects/shadow-a"; }
}
