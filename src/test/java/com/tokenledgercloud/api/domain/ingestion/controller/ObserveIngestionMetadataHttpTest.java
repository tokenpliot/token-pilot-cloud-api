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
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * Observe 수집(v2)의 metadata를 기본 모드(금지 키는 버리고 형식 위반은 경고만)로 확인한다.
 * 실제 보안 필터, 키 검증, H2를 거친다. 아무것도 가짜로 바꾸지 않는다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:observe-metadata-http;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.enabled=true"
})
class ObserveIngestionMetadataHttpTest {

	private static final String KEY = "tp_observe_metadata_secret";

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
		projects.saveAndFlush(Project.builder().id("meta-project").organizationId("org-1").projectKey("alpha")
			.name("alpha").status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("meta-project")
			.environment("prod").build());
		keys.saveAndFlush(ProjectApiKey.builder().id("meta-key").organizationId("org-1").projectId("meta-project")
			.environment("prod").name("meta-key").keyPrefix(KEY).keyHash(encoder.encode(KEY)).status("ACTIVE").build());
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
	void metadataIsStoredInMetadataJson() throws Exception {
		send(event("evt-meta", "{\"trace_id\":\"trace-1\",\"app_request_id\":\"app-1\"}"))
			.andExpect(status().isCreated());
		// H2의 json 컬럼은 문자열을 한 번 더 감싸 돌려주므로 v1 테스트처럼 내용 포함 여부로 확인한다.
		assertThat(row("evt-meta").getMetadataJson()).contains("trace_id", "trace-1", "app_request_id", "app-1");
	}

	@Test
	void forbiddenKeyIsDroppedAndNotStored() throws Exception {
		send(event("evt-forbidden", "{\"trace_id\":\"trace-1\",\"prompt\":\"SECRET-PROMPT-TEXT\"}"))
			.andExpect(status().isCreated());
		assertThat(row("evt-forbidden").getMetadataJson())
			.contains("trace_id")
			.doesNotContain("prompt", "SECRET-PROMPT-TEXT");
	}

	@Test
	void onlyForbiddenKeysStoreNoMetadata() throws Exception {
		send(event("evt-only-forbidden", "{\"messages\":[\"SECRET\"]}")).andExpect(status().isCreated());
		assertThat(row("evt-only-forbidden").getMetadataJson()).isNull();
	}

	@Test
	void sameMetadataInDifferentKeyOrderIsADuplicate() throws Exception {
		send(event("evt-order", "{\"trace_id\":\"trace-1\",\"app_request_id\":\"app-1\"}"))
			.andExpect(status().isCreated());
		send(event("evt-order", "{\"app_request_id\":\"app-1\",\"trace_id\":\"trace-1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true));
		assertThat(usage.findAll()).hasSize(1);
	}

	@Test
	void forbiddenKeyDoesNotChangeTheFingerprint() throws Exception {
		// 금지 키는 지문을 계산하기 전에 버려지므로, 있든 없든 같은 이벤트다.
		send(event("evt-forbidden-dup", "{\"trace_id\":\"trace-1\"}")).andExpect(status().isCreated());
		send(event("evt-forbidden-dup", "{\"trace_id\":\"trace-1\",\"completion\":\"SECRET\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true));
	}

	@Test
	void differentMetadataForTheSameEventIdIsAConflict() throws Exception {
		send(event("evt-conflict", "{\"trace_id\":\"trace-1\"}")).andExpect(status().isCreated());
		send(event("evt-conflict", "{\"trace_id\":\"trace-2\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INGESTION-409"));
		// metadata를 빼고 보내도 처음 본문과 다르므로 충돌이다.
		send(event("evt-conflict", null)).andExpect(status().isConflict());
		assertThat(usage.findAll()).hasSize(1);
	}

	@Test
	void requestWithoutMetadataKeepsThePreMetadataFingerprint() throws Exception {
		send(event("evt-legacy", null)).andExpect(status().isCreated());
		UsageLog stored = row("evt-legacy");
		assertThat(stored.getMetadataJson()).isNull();
		// metadata가 생기기 전의 계산식으로 직접 구한 값과 같아야, 이미 저장된 행도 재전송 시 중복으로 인식된다.
		assertThat(stored.getPayloadFingerprint()).isEqualTo(preMetadataFingerprint());
		// 빈 metadata는 metadata가 없는 것과 같은 이벤트다.
		send(event("evt-legacy", "{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true));
	}

	@Test
	void formatViolationIsAcceptedInDefaultMode() throws Exception {
		send(event("evt-format", "{\"Bad-Key\":\"v\",\"count\":5}")).andExpect(status().isCreated());
		assertThat(row("evt-format").getMetadataJson()).contains("Bad-Key", "count");
	}

	private ResultActions send(String body) throws Exception {
		return mvc.perform(post("/api/v2/observe/events").header("X-API-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private UsageLog row(String eventId) {
		return usage.findAll().stream().filter(log -> eventId.equals(log.getEventId())).findFirst().orElseThrow();
	}

	private static String event(String eventId, String metadataJson) {
		String metadata = metadataJson == null ? "" : ",\"metadata\":" + metadataJson;
		return """
			{"projectKey":"alpha","environment":"prod","eventId":"%s","schemaVersion":"observe-v1",
			 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"m","callStatus":"SUCCEEDED",
			 "promptTokens":10,"completionTokens":5,"occurredAt":"2026-10-01T00:00:00Z"%s}
			""".formatted(eventId, metadata);
	}

	/** metadata 지원 전의 observe-fp-v1 계산식을 그대로 옮긴 것. event(..., null) 본문의 지문이다. */
	private static String preMetadataFingerprint() {
		StringBuilder canonical = new StringBuilder("observe-fp-v1\n");
		field(canonical, "requestId", null);
		field(canonical, "schemaVersion", "observe-v1");
		field(canonical, "tokenSemantics", "INCLUSIVE_V1");
		field(canonical, "provider", "openai");
		field(canonical, "model", "m");
		field(canonical, "callStatus", "SUCCEEDED");
		field(canonical, "promptTokens", "10");
		field(canonical, "completionTokens", "5");
		field(canonical, "cachedPromptTokens", null);
		field(canonical, "reasoningTokens", null);
		field(canonical, "occurredAt", "2026-10-01T00:00");
		return HashingUtil.sha256(canonical.toString());
	}

	private static void field(StringBuilder out, String name, String value) {
		String text = value == null ? "" : value;
		out.append(name).append(':').append(value == null ? '-' : '+').append(text.length()).append(':')
			.append(text).append('\n');
	}
}
