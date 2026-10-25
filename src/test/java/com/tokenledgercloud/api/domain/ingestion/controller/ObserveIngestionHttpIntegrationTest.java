package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.ingestion.web.IngestionRequestSizeFilter;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/** POST /api/v2/observe/events through the real security chain, filters, BCrypt key check and H2; nothing mocked. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:observe-http;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.enabled=true",
	"token-pilot.ingestion.max-event-bytes=2048"
})
class ObserveIngestionHttpIntegrationTest {

	private static final String PROD_KEY = "tp_observe_prod_secret";
	private static final String ANY_ENV_KEY = "tp_observe_any_env_secret";

	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;
	@Autowired IngestionRequestSizeFilter sizeFilter;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository keys;
	@Autowired UsageLogRepository usage;
	@Autowired UsageAccountingValueRepository values;
	@Autowired PasswordEncoder encoder;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		// The size filter is a servlet filter ordered before Spring Security, as in the running server.
		mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sizeFilter, securityFilterChain).build();
		projects.saveAndFlush(Project.builder().id("observe-project").organizationId("org-1").projectKey("alpha")
			.name("alpha").status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("observe-project")
			.environment("prod").build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("observe-project")
			.environment("staging").build());
		key("observe-key-prod", PROD_KEY, "prod");
		// No environment restriction: valid for every environment of the project.
		key("observe-key-any", ANY_ENV_KEY, null);
	}

	/** The ledger writer commits in REQUIRES_NEW, so clean up explicitly. */
	@AfterEach
	void tearDown() {
		values.deleteAll();
		usage.deleteAll();
		keys.deleteAll();
		environments.deleteAll();
		projects.deleteAll();
	}

	@Test
	void projectKeyReachesTheServiceAndResendIsADuplicate() throws Exception {
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", PROD_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-1", "prod")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.duplicate").value(false));
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", PROD_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-1", "prod")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true));
		assertThat(usage.findAll()).hasSize(1);
		assertThat(values.findAll()).hasSize(1);
	}

	@Test
	void keyWithoutEnvironmentRestrictionStoresTheRequestedEnvironment() throws Exception {
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", ANY_ENV_KEY)
				.contentType(MediaType.APPLICATION_JSON).content(event("evt-any", "staging")))
			.andExpect(status().isCreated());
		assertThat(usage.findAll()).singleElement()
			.satisfies(row -> assertThat(row.getEnvironment()).isEqualTo("staging"));
	}

	@Test
	void oversizedBodyIsRejectedBeforeAuthentication() throws Exception {
		String oversized = event("evt-big", "prod").replace("\"model\":\"m\"", "\"model\":\"" + "m".repeat(4096) + "\"");
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", "not-a-real-key")
				.contentType(MediaType.APPLICATION_JSON).content(oversized))
			.andExpect(status().isPayloadTooLarge());
		assertThat(usage.findAll()).isEmpty();
	}

	private void key(String id, String raw, String environment) {
		keys.saveAndFlush(ProjectApiKey.builder().id(id).organizationId("org-1").projectId("observe-project")
			.environment(environment).name(id).keyPrefix(raw).keyHash(encoder.encode(raw)).status("ACTIVE").build());
	}

	private static String event(String eventId, String environment) {
		return """
			{"projectKey":"alpha","environment":"%s","eventId":"%s","schemaVersion":"observe-v1",
			 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"m","callStatus":"SUCCEEDED",
			 "promptTokens":10,"completionTokens":5,"occurredAt":"2026-10-01T00:00:00Z"}
			""".formatted(environment, eventId);
	}
}
