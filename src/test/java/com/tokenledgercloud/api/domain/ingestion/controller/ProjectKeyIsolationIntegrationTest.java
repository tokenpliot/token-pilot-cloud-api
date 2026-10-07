package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/** Real security chain, BCrypt authentication, services and H2 repositories; no mocked authorization. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:key-isolation;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
@Transactional
class ProjectKeyIsolationIntegrationTest {
    private static final String KEY_A = "tp_project_a_secret";
    private static final String KEY_B = "tp_project_b_secret";
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy securityFilterChain;
    @Autowired ProjectRepository projects;
    @Autowired ProjectEnvironmentRepository environments;
    @Autowired ProjectApiKeyRepository keys;
    @Autowired UsageLogRepository usage;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;
    ProjectApiKey keyA;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
        project("project-a", "org-a", "alpha");
        project("project-b", "org-a", "beta");
        project("project-c", "org-b", "gamma");
        // projectKey is unique only inside its organization.
        project("project-d", "org-b", "alpha");
        keyA = key("key-a", "org-a", "project-a", KEY_A, "prod");
        key("key-b", "org-a", "project-b", KEY_B, "prod");
    }

    @Test
    void validKeysWriteOnlyTheirOwnProjectAndDeduplicateWithinThatScope() throws Exception {
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(event("alpha", "prod")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.accepted").value(true));
        String firstId = usage.findAll().getFirst().getId();
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(event("alpha", "prod")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.eventId").value(firstId));
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_B)
                        .contentType(MediaType.APPLICATION_JSON).content(event("beta", "prod")))
                .andExpect(status().isCreated());
        assertThat(usage.findAll()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getOrganizationId()).isEqualTo("org-a");
            assertThat(row.getProjectId()).isIn("project-a", "project-b");
            assertThat(row.getApiKeyId()).isEqualTo(row.getProjectId().equals("project-a") ? "key-a" : "key-b");
        });
        assertThat(keyA.getLastUsedAt()).isNotNull();
    }

    @Test
    void keyCannotWriteAnotherProjectInSameOrganization() throws Exception {
        rejected(KEY_A, "beta", "prod", 403);
        assertThat(usage.count()).isZero();
        assertThat(keyA.getLastUsedAt()).isNull();
    }

    @Test
    void keyCannotDiscoverProjectInAnotherOrganization() throws Exception {
        rejected(KEY_A, "gamma", "prod", 404);
        assertThat(usage.count()).isZero();
    }

    @Test
    void batchAuthorizationFailsBeforeAnyItemIsWritten() throws Exception {
        mvc.perform(post("/api/ingestion/events/batch").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"beta","environment":"prod","items":[%s]}
                                """.formatted(item())))
                .andExpect(status().isForbidden());
        assertThat(usage.count()).isZero();
        assertThat(keyA.getLastUsedAt()).isNull();
    }

    @Test
    void validBatchUsesProjectAuthentication() throws Exception {
        mvc.perform(post("/api/ingestion/events/batch").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"alpha","environment":"prod","items":[%s]}
                                """.formatted(item())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.acceptedCount").value(1));
        assertThat(usage.findAll()).singleElement().satisfies(row -> assertThat(row.getProjectId()).isEqualTo("project-a"));
    }

    @Test
    void scopedKeyRejectsOtherEnvironmentButDoesNotRequireLegacyEnvironmentRows() throws Exception {
        rejected(KEY_A, "alpha", "dev", 403);
        environments.deleteByProjectId("project-a");
        environments.flush();
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(event("alpha", "prod")))
                .andExpect(status().isCreated());
        assertThat(usage.findAll()).singleElement().satisfies(row ->
                assertThat(row.getEnvironment()).isEqualTo("prod"));
    }

    @Test
    void wildcardKeyRetainsLegacyUnregisteredEnvironmentSupportWithinOwnProject() throws Exception {
        keyA.setEnvironment(null);
        keys.saveAndFlush(keyA);
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(event("alpha", "dev")))
                .andExpect(status().isCreated());
        rejected(KEY_A, "beta", "dev", 403);
        assertThat(usage.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getProjectId()).isEqualTo("project-a");
            assertThat(row.getEnvironment()).isEqualTo("dev");
        });
    }

    @Test
    @ResourceLock("java.util.TimeZone.default")
    void legacyLocalExpiryRemainsExpiredInSeoulTimezone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            keyA.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            keys.saveAndFlush(keyA);
            rejected(KEY_A, "alpha", "prod", 401);
            assertThat(usage.count()).isZero();
            assertThat(keyA.getLastUsedAt()).isNull();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void foreignProjectDuplicateCannotReturnItsExistingEvent() throws Exception {
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_B)
                        .contentType(MediaType.APPLICATION_JSON).content(event("beta", "prod")))
                .andExpect(status().isCreated());
        String foreignEventId = usage.findAll().getFirst().getId();
        String response = mvc.perform(post("/api/ingestion/events").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content(event("beta", "prod")))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(foreignEventId);
        assertThat(usage.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(foreignEventId);
            assertThat(row.getProjectId()).isEqualTo("project-b");
        });
        assertThat(keyA.getLastUsedAt()).isNull();
    }

    @Test
    void invalidRevokedExpiredAndMissingKeysAreRejected() throws Exception {
        rejected("invalid", "alpha", "prod", 401);
        mvc.perform(post("/api/ingestion/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("alpha", "prod"))).andExpect(status().isUnauthorized());
        keyA.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        keys.saveAndFlush(keyA);
        rejected(KEY_A, "alpha", "prod", 401);
        keyA.setExpiresAt(null);
        keyA.setStatus("REVOKED");
        keys.saveAndFlush(keyA);
        rejected(KEY_A, "alpha", "prod", 401);
        assertThat(usage.count()).isZero();
    }

    @Test
    void inactiveProjectOrCorruptKeyTenantCannotWrite() throws Exception {
        Project project = projects.findById("project-a").orElseThrow();
        project.setStatus(ProjectStatus.ARCHIVED);
        projects.saveAndFlush(project);
        rejected(KEY_A, "alpha", "prod", 403);
        project.setStatus(ProjectStatus.ACTIVE);
        projects.saveAndFlush(project);
        keyA.setOrganizationId("org-b");
        keys.saveAndFlush(keyA);
        rejected(KEY_A, "alpha", "prod", 403);
        assertThat(usage.count()).isZero();
    }

    @Test
    void memberKeyFilterRejectsProjectKeysOutsideIngestion() throws Exception {
        // Authentication namespace regression only; anonymous/member tenant isolation is not established here.
        for (String path : List.of("/api/dashboard/kpi?projectId=project-b&period=week",
                "/api/usage-events?projectId=project-b", "/api/projects/project-b")) {
            mvc.perform(get(path).header("X-API-Key", KEY_A)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/internal/usage-logs").header("X-API-Key", KEY_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(usage.count()).isZero();
    }

    private void rejected(String rawKey, String projectKey, String environment, int expected) throws Exception {
        mvc.perform(post("/api/ingestion/events").header("X-API-Key", rawKey)
                        .contentType(MediaType.APPLICATION_JSON).content(event(projectKey, environment)))
                .andExpect(status().is(expected));
    }

    private void project(String id, String organizationId, String projectKey) {
        projects.saveAndFlush(Project.builder().id(id).organizationId(organizationId).projectKey(projectKey)
                .name(projectKey).status(ProjectStatus.ACTIVE).build());
        environments.saveAndFlush(ProjectEnvironment.builder().organizationId(organizationId)
                .projectId(id).environment("prod").build());
    }

    private ProjectApiKey key(String id, String org, String project, String raw, String environment) {
        return keys.saveAndFlush(ProjectApiKey.builder().id(id).organizationId(org).projectId(project)
                .environment(environment).name(id).keyPrefix(raw).keyHash(encoder.encode(raw)).status("ACTIVE").build());
    }

    private String event(String project, String environment) {
        return "{\"projectKey\":\"%s\",\"environment\":\"%s\",%s}".formatted(project, environment,
                item().strip().substring(1, item().strip().length() - 1));
    }

    private String item() {
        return """
                {"requestId":"same-request","provider":"openai","model":"test-model",
                 "promptTokens":12,"completionTokens":4,"totalTokens":16,
                 "totalCostUsd":0.000001,"pricingVersion":"test-v1","occurredAt":"2026-10-01T00:00:00Z"}
                """;
    }
}
