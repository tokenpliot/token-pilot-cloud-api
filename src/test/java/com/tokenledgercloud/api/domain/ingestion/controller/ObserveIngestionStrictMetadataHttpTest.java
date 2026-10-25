package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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

/** 엄격 모드(token-pilot.ingestion.strict-metadata=true)에서 Observe 수집(v2)의 metadata가 v1과 같이 400으로 거절되는지 확인한다. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:observe-metadata-strict-http;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.enabled=true",
	"token-pilot.ingestion.strict-metadata=true"
})
class ObserveIngestionStrictMetadataHttpTest {

	private static final String KEY = "tp_observe_strict_secret";

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
		mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sizeFilter, securityFilterChain).build();
		projects.saveAndFlush(Project.builder().id("strict-project").organizationId("org-1").projectKey("alpha")
			.name("alpha").status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("strict-project")
			.environment("prod").build());
		keys.saveAndFlush(ProjectApiKey.builder().id("strict-key").organizationId("org-1").projectId("strict-project")
			.environment("prod").name("strict-key").keyPrefix(KEY).keyHash(encoder.encode(KEY)).status("ACTIVE").build());
	}

	/** 원장 writer가 REQUIRES_NEW로 커밋하므로 직접 정리한다. */
	@AfterEach
	void tearDown() {
		values.deleteAll();
		usage.deleteAll();
		keys.deleteAll();
		environments.deleteAll();
		projects.deleteAll();
	}

	@Test
	void moreThanSixteenEntriesAreRejected() throws Exception {
		String seventeen = IntStream.rangeClosed(1, 17)
			.mapToObj(i -> "\"k" + i + "\":\"v\"")
			.collect(Collectors.joining(",", "{", "}"));
		send(event("evt-too-many", seventeen))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON-400"))
			.andExpect(jsonPath("$.errors[0].field").value("metadata"));
		assertThat(usage.findAll()).isEmpty();
	}

	@Test
	void forbiddenKeyIsRejected() throws Exception {
		send(event("evt-forbidden", "{\"prompt\":\"SECRET-PROMPT-TEXT\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("metadata"));
		assertThat(usage.findAll()).isEmpty();
	}

	@Test
	void wellFormedMetadataIsAccepted() throws Exception {
		send(event("evt-ok", "{\"trace_id\":\"trace-1\",\"app_request_id\":\"app-1\"}"))
			.andExpect(status().isCreated());
		assertThat(usage.findAll()).singleElement()
			.satisfies(row -> assertThat(row.getMetadataJson()).contains("trace-1", "app-1"));
	}

	private ResultActions send(String body) throws Exception {
		return mvc.perform(post("/api/v2/observe/events").header("X-API-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String event(String eventId, String metadataJson) {
		return """
			{"projectKey":"alpha","environment":"prod","eventId":"%s","schemaVersion":"observe-v1",
			 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"m","callStatus":"SUCCEEDED",
			 "promptTokens":10,"completionTokens":5,"occurredAt":"2026-10-01T00:00:00Z","metadata":%s}
			""".formatted(eventId, metadataJson);
	}
}
