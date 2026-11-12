package com.tokenledgercloud.api.domain.shadow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import com.fasterxml.jackson.databind.*;
import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    "token-pilot.shadow.enabled=true", "token-pilot.decision.enabled=true",
    "token-pilot.observe.ingestion.rate-unit-tokens=1000000",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.flyway.enabled=false"
})
class ShadowIntegrationTest {
    @Autowired ShadowService shadow;
    @Autowired DecisionExecutionService executions;
    @Autowired ProjectApiKeyRepository apiKeys;
    @Autowired PasswordEncoder encoder;
    @Autowired PricingPlanRepository plans;
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
    String key;
    String foreignKey;
    String devKey;
    final String month = YearMonth.now(ZoneOffset.UTC).toString();
    final ObjectMapper json = new ObjectMapper();
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
        plans.saveAndFlush(PricingPlan.builder().id("shadow-plan").catalogId("shadow-catalog")
            .provider("test").model("model").currency("USD").promptRate(BigDecimal.ONE).completionRate(BigDecimal.ONE)
            .version("price-1").effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        var fixtures = new ProjectKeyFixtures(projects, apiKeys, encoder);
        key = fixtures.key(projects.findById("shadow-a").orElseThrow(), "prod", "ACTIVE", null).raw();
        devKey = fixtures.key(projects.findById("shadow-a").orElseThrow(), "dev", "ACTIVE", null).raw();
        foreignKey = fixtures.key(projects.findById("shadow-b").orElseThrow(), "prod", "ACTIVE", null).raw();

    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from shadow_audits where kind <> 'RUN'");
        jdbc.update("delete from shadow_audits");
        for (String table : List.of("decision_execution_events", "decision_records", "policy_request_contexts", "usage_accounting_values", "usage_events",
            "policy_snapshots", "customer_policies", "monthly_budget_settings", "pricing_snapshots",
            "pricing_plans", "organization_members", "project_api_keys", "project_environments", "projects", "users")) jdbc.update("delete from " + table);
    }

    @Test
    void advisoryHttpDecisionsReplayAndExecutionEvidenceFlagsCandidatesWithoutActivatingPolicy() throws Exception {
        decide("one", 2); var expensive = decide("two", 8); decide("three", 1);
        mvc.perform(post(path() + "/runs").header("Authorization", bearer("shadow-admin"))
            .header("Idempotency-Key", "http-replay-1").contentType(MediaType.APPLICATION_JSON)
            .content(ShadowJson.write(request("100", "5"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.candidateDenied").value(1))
            .andExpect(jsonPath("$.data.summary.candidateEvaluated").value(3))
            .andExpect(jsonPath("$.data.summary.candidateDenialRate").value(0.333333))
            .andExpect(jsonPath("$.data.summary.falseDenialCandidates").value(0));
        var run = shadow.runs(admin, "shadow-a", 0, 10).getFirst();
        var event = execution(expensive, ActualExecution.PROCEEDED, CustomerOverride.NONE);
        mvc.perform(post("/api/control/v1/execution-events").header("X-API-Key", key)
            .header("Idempotency-Key", "exec-two-1").contentType(MediaType.APPLICATION_JSON).content(ShadowJson.write(event)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.decisionId").value(expensive.decisionId()))
            .andExpect(jsonPath("$.data.serverOutcome").value("ALLOW"));
        assertThat(shadow.report(admin, "shadow-a", run.runId()).summary().falseDenialCandidates()).isEqualTo(1);
        assertThat(shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, true, true).items()).singleElement().satisfies(d -> {
            assertThat(d.sample().requestId()).isEqualTo("two");
            assertThat(d.replayStatus()).isEqualTo("REPRODUCED");
            assertThat(d.after().findings()).extracting(Finding::code).contains("REQUEST_COST_LIMIT_EXCEEDED");
            assertThat(d.sample().pricingVersion()).isEqualTo("price-1");
            assertThat(d.before().requiredCostUsd()).isEqualTo("8.000000");
        });
        assertThat(policies.current(admin, "shadow-a", "prod").snapshotId()).isEqualTo(baseline.snapshotId());
        assertThat(jdbc.queryForObject("select count(*) from policy_snapshots", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from usage_events", Integer.class)).isZero();
    }

    @Test
    void originalPolicyPriceAndBalancesAreReusedAfterLiveChanges() throws Exception {
        var original = decide("frozen", 8);
        var first = shadow.run(admin, "shadow-a", "freeze-key", request("100", "5"));
        policies.replace(admin, "shadow-a", "prod", new PolicyRequests.Replace(1L, rules("1", "1", true), "Changed"));
        jdbc.update("update pricing_plans set prompt_rate=99, version='price-2'");
        var later = shadow.run(admin, "shadow-a", "later-key", request("100", "5"));
        var row = shadow.decisions(admin, "shadow-a", later.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.replayStatus()).isEqualTo("REPRODUCED");
        assertThat(row.before().requiredCostUsd()).isEqualTo(original.requiredCostUsd());
        assertThat(row.sample().pricingVersion()).isEqualTo("price-1");
        assertThat(row.sample().policies()).extracting(PolicyBinding::version).containsExactly(baseline.version());
        assertThat(later.summary()).isEqualTo(first.summary());
        assertThat(shadow.run(admin, "shadow-a", "freeze-key", request("100", "5"))).isEqualTo(first);
    }

    @Test
    void missingPriceAndUnsupportedAlgorithmStayIndeterminate() throws Exception {
        decide("missing", "prod", "missing-model", 1, key);
        var version = decide("unsupported", 1);
        jdbc.update("update decision_records set algorithm_version='future-v2' where id=?", version.decisionId().substring(4));
        var run = shadow.run(admin, "shadow-a", "unknown-key", request("100", "5"));
        assertThat(run.summary().sampled()).isEqualTo(2);
        assertThat(run.summary().indeterminate()).isEqualTo(2);
        assertThat(run.summary().candidateDenialRate()).isNull();
        assertThat(run.rolloutReadiness()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items())
            .extracting(Decision::replayStatus).containsExactly("REPRODUCED", "UNSUPPORTED_ALGORITHM");
    }

    @Test
    void activatingAnInactiveScopeDoesNotTreatUncapturedSpendAsZero() throws Exception {
        baseline = policies.replace(admin, "shadow-a", "prod", new PolicyRequests.Replace(1L, rules("100", "50", false), "Disable"));
        decide("inactive", 1);
        var run = shadow.run(admin, "shadow-a", "activate-key", request("100", "5"));
        var row = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.before().outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(row.after().outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(row.replayStatus()).isEqualTo("INACTIVE_SCOPE_SPEND_NOT_CAPTURED");
    }

    @Test
    void companionProjectPolicyAndOriginalSpendRemainEffective() throws Exception {
        var companion = policies.replace(admin, "shadow-a", null, new PolicyRequests.Replace(0L, rules("95", null, true), "Project"));
        event("other-env", "dev", 1, "90");
        decide("target", 10);
        policies.replace(admin, "shadow-a", null, new PolicyRequests.Replace(1L, rules("1000", null, true), "Changed later"));
        var run = shadow.run(admin, "shadow-a", "project-scope", new RunRequest(baseline.snapshotId(), month,
            rules("1000", null, false), "Disable environment only"));
        var row = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.before().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(row.after().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(row.replayStatus()).isEqualTo("REPRODUCED");
        assertThat(row.sample().policies()).extracting(PolicyBinding::version).contains(companion.version());
        assertThat(row.sample().evaluationInputJson()).contains("90.000000");
    }

    @Test
    void softLimitsWarnAtEqualHardBoundaryAndUpperBoundIncludesExpensiveCacheAndReasoning() throws Exception {
        jdbc.update("update pricing_plans set cached_prompt_rate=2, reasoning_rate=3");
        decide("upper-bound", 2); // 2m input * max(1,2) + 0 output => $4
        var candidate = new PolicyRules("USD", new BigDecimal("3"), new BigDecimal("4"),
            new BigDecimal("3"), new BigDecimal("4"), true, List.of(80));
        var run = shadow.run(admin, "shadow-a", "boundary-key", new RunRequest(baseline.snapshotId(), month, candidate, "Boundary"));
        var row = shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items().getFirst();
        assertThat(row.after().requiredCostUsd()).isEqualTo("4.000000");
        assertThat(row.after().outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(row.after().findings()).extracting(Finding::code).contains("MONTHLY_SOFT_LIMIT_EXCEEDED", "REQUEST_COST_SOFT_LIMIT_EXCEEDED");
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
        for (String suffix : List.of("", "/decisions")) {
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
            new RunRequest(foreignBase.snapshotId(), month, rules("100", "5", true), "Compare")).runId()).isNotEqualTo(first.runId());
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
    void customerOutageOverrideAndPolicyDenialAreSeparateIdempotentAuditFacts() throws Exception {
        var denied = decide("denied", 60);
        var run = shadow.run(admin, "shadow-a", "feedback-run", request("100", "5"));
        var blocked = execution(denied, ActualExecution.BLOCKED, CustomerOverride.NONE);
        var first = executions.record(key, "blocked-event", blocked);
        assertThat(first.categories()).containsExactly("POLICY_DENIAL");
        assertThat(executions.record(key, "blocked-event", blocked)).isEqualTo(first);
        var outage = new ExecutionCommand("2026-10-01", "outage", "prod", null, DecisionOutcome.CONTROL_PLANE_UNAVAILABLE,
            ActualExecution.PROCEEDED, CustomerOverride.ALLOW, EnforcementMode.ADVISORY, Instant.now().toString(), "TIMEOUT");
        var unavailable = executions.record(key, "outage-event", outage);
        assertThat(unavailable.serverOutcome()).isNull();
        assertThat(unavailable.categories()).containsExactly("SERVER_UNAVAILABLE", "CUSTOMER_OVERRIDE");
        assertThat(unavailable.evidenceSource()).isEqualTo("CUSTOMER_REPORTED_PROJECT_KEY");
        var report = shadow.report(admin, "shadow-a", run.runId());
        assertThat(report.customerFeedbackEvents()).isEqualTo(2);
        assertThat(report.serverUnavailableEvents()).isEqualTo(1);
        assertThat(report.customerOverrideEvents()).isEqualTo(1);
        assertThat(report.summary()).isEqualTo(run.summary());
        assertThat(shadow.executions(admin, "shadow-a", null, denied.decisionId(), "POLICY_DENIAL", 0, 20)).containsExactly(first);
        assertThat(shadow.executions(admin, "shadow-a", null, null, "SERVER_UNAVAILABLE", 0, 20)).containsExactly(unavailable);
        assertThatThrownBy(() -> executions.record(key, "outage-event", blocked))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
        assertThat(executions.record(foreignKey, "outage-event", outage).id()).isNotEqualTo(unavailable.id());
    }

    @Test
    void projectKeysCannotReferenceOtherProjectEnvironmentOrRequestAndCannotForgeServerDenial() throws Exception {
        var result = decide("r".repeat(128), 1);
        var valid = execution(result, ActualExecution.PROCEEDED, CustomerOverride.NONE);
        postExecution(foreignKey, valid).andExpect(status().isNotFound());
        postExecution(devKey, valid).andExpect(status().isForbidden());
        String body = ShadowJson.write(valid);
        postExecutionBody(key, body.replace(result.requestId(), "wrong-request")).andExpect(status().isNotFound());
        postExecutionBody(key, body.replace("\"ALLOW\"", "\"DENY_BUDGET\"")).andExpect(status().isBadRequest());
        postExecutionBody(key, body.replace("\"" + result.decisionId() + "\"", "null")
            .replace("\"ALLOW\"", "\"DENY_BUDGET\"")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from decision_execution_events", Integer.class)).isZero();
        postExecution(key, valid).andExpect(status().isOk());
        mvc.perform(get(path() + "/execution-events").header("Authorization", bearer("shadow-outsider")))
            .andExpect(status().isNotFound());
        mvc.perform(get(path() + "/execution-events").header("Authorization", bearer("shadow-viewer")))
            .andExpect(status().isOk());
    }

    @Test
    void invalidExecutionPayloadDoesNotPersistOrEchoUnknownCustomerText() throws Exception {
        var result = decide("invalid-event", 1);
        var valid = execution(result, ActualExecution.PROCEEDED, CustomerOverride.NONE);
        String body = ShadowJson.write(valid);
        String secret = "DO_NOT_SAVE_CUSTOMER_PROMPT";
        var response = postExecutionBody(key, body.substring(0, body.length()-1) + ",\"prompt\":\"" + secret + "\"}")
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(secret);
        postExecutionBody(key, body.replace("\"NONE\"", "\"BLOCK\"")).andExpect(status().isBadRequest());
        postExecutionBody(key, body.replace("2026-10-01", "bad-version")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/control/v1/execution-events").header("Idempotency-Key", "invalid-event")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from decision_execution_events", Integer.class)).isZero();
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
        for (String invalid : List.of("{", body.replace(month, "2026-13"),
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
    void concurrentExecutionRetriesCreateOneAuditEvent() throws Exception {
        var command = execution(decide("concurrent-exec", 1), ActualExecution.PROCEEDED, CustomerOverride.NONE);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> call = () -> { start.await(); return executions.record(key, "concurrent-exec-key", command).id(); };
            var first = pool.submit(call);
            var second = pool.submit(call);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from decision_execution_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void oversizedCohortIsRejectedWithoutSavingATruncatedReport() throws Exception {
        var source = decide("cohort-source", 1);
        String sourceId = source.decisionId().substring(4);
        String contextId = jdbc.queryForObject("select policy_request_context_id from decision_records where id=?", String.class, sourceId);
        List<Object[]> contexts = new ArrayList<>();
        List<Object[]> records = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String id = UUID.randomUUID().toString();
            contexts.add(new Object[]{id, "sample-" + i, contextId});
            records.add(new Object[]{UUID.randomUUID().toString(), id, "cohort-key-" + i, sourceId});
        }
        // Clone immutable evidence only in test fixtures; the limit must reject before attempting any replay.
        jdbc.batchUpdate("""
            insert into policy_request_contexts (id, organization_id, project_id, environment, request_id,
                project_policy_snapshot_id, environment_policy_snapshot_id, pricing_snapshot_id, actor_type, actor_id, created_at)
            select ?, organization_id, project_id, environment, ?, project_policy_snapshot_id,
                environment_policy_snapshot_id, pricing_snapshot_id, actor_type, actor_id, created_at
            from policy_request_contexts where id=?
            """, contexts);
        jdbc.batchUpdate("""
            insert into decision_records (id, organization_id, project_id, api_key_id, policy_request_context_id,
                idempotency_key, request_fingerprint, client_mode, outcome, algorithm_version, evaluated_at,
                evaluation_input_json, response_json, created_at)
            select ?, organization_id, project_id, api_key_id, ?, ?, request_fingerprint, client_mode, outcome,
                algorithm_version, evaluated_at, evaluation_input_json, response_json, created_at
            from decision_records where id=?
            """, records);
        assertThatThrownBy(() -> shadow.run(admin, "shadow-a", "oversized-run", request("100", "5")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(jdbc.queryForObject("select count(*) from shadow_audits", Integer.class)).isZero();
    }

    @Test
    void exactBaselineMonthAndEnvironmentDetermineTheSample() throws Exception {
        var previous = decide("previous-month", 80);
        var next = decide("next-month", 80);
        jdbc.update("update decision_records set evaluated_at=? where id=?", YearMonth.parse(month).atDay(1).atStartOfDay().minusSeconds(1), previous.decisionId().substring(4));
        jdbc.update("update decision_records set evaluated_at=? where id=?", YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(), next.decisionId().substring(4));
        decide("other-env", "dev", "model", 80, devKey);
        decide("in-month", 2);
        policies.replace(admin, "shadow-a", "prod", new PolicyRequests.Replace(1L, rules("100", "50", true), "New baseline"));
        decide("different-baseline", 3);
        var run = shadow.run(admin, "shadow-a", "month-boundaries", request("5", "5"));
        assertThat(shadow.decisions(admin, "shadow-a", run.runId(), 0, 10, false, false).items())
            .extracting(d -> d.sample().requestId()).containsExactly("in-month");
        assertThat(run.summary().candidateDenied()).isZero();
    }

    private DecisionResponse decide(String id, long millions) throws Exception { return decide(id, "prod", "model", millions, key); }
    private DecisionResponse decide(String id, String env, String model, long millions, String apiKey) throws Exception {
        String body = json.writeValueAsString(Map.of("schemaVersion", "2026-10-01", "requestId", id, "environment", env,
            "provider", "test", "model", model, "clientMode", "ADVISORY", "estimatedInputTokens", millions * 1000000, "maxOutputTokens", 0));
        String response = mvc.perform(post("/api/control/v1/decisions").header("X-API-Key", apiKey)
            .header("Idempotency-Key", "decision-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.treeToValue(json.readTree(response).path("data"), DecisionResponse.class);
    }
    private ExecutionCommand execution(DecisionResponse d, ActualExecution actual, CustomerOverride override) {
        return new ExecutionCommand("2026-10-01", d.requestId(), "prod", d.decisionId(), d.outcome(), actual, override,
            EnforcementMode.ADVISORY, Instant.now().toString(), "CUSTOMER_RESULT");
    }
    private org.springframework.test.web.servlet.ResultActions postExecution(String apiKey, ExecutionCommand command) throws Exception {
        return postExecutionBody(apiKey, ShadowJson.write(command));
    }
    private org.springframework.test.web.servlet.ResultActions postExecutionBody(String apiKey, String body) throws Exception {
        return mvc.perform(post("/api/control/v1/execution-events").header("X-API-Key", apiKey)
            .header("Idempotency-Key", "execution-key").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UsageLog event(String id, String env, int minute, String amount) {
        UsageLog row = usage.saveAndFlush(UsageLog.builder().id(id).organizationId("shadow-org-a").projectId("shadow-a")
            .environment(env).requestId("request-" + id).provider("test").model("model").pricingVersion("price-1")
            .accountingStatus("VERIFIED").callStatus("SUCCEEDED").settlementState("UNSETTLED").tokenSemantics("INCLUSIVE_V1")
            .schemaVersion("observe-v1").occurredAt(YearMonth.parse(month).atDay(1).atTime(0, minute)).build());
        if (amount != null) actual(row, 1, amount);
        return row;
    }
    private void actual(UsageLog event, int revision, String amount) {
        values.saveAndFlush(UsageAccountingValue.of(event.getId(), revision,
            new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED, new BigDecimal(amount),
                "USD", AccountingAmount.Source.PROVIDER_BILLING), null, "fixture-invoice", "TEST", "SYSTEM", "test"));
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
        return new RunRequest(baseline.snapshotId(), month, rules(monthly, perRequest, true), "Compare");
    }
    private static PolicyRules rules(String monthly, String perRequest, boolean active) {
        return new PolicyRules("USD", null, new BigDecimal(monthly), null,
            perRequest == null ? null : new BigDecimal(perRequest), active, List.of(80));
    }
    private Authentication auth(String member) { return jwt.getAuthentication(jwt.createToken(member + "@example.test", "USER")); }
    private String bearer(String member) { return "Bearer " + jwt.createToken(member + "@example.test", "USER"); }
    private String path() { return "/api/shadow/projects/shadow-a"; }
}
