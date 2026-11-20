package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.event.repository.OperationalEventRepository;
import com.tokenledgercloud.api.domain.ingestion.web.IngestionRequestSizeFilter;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.entity.ProjectApiKey;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * SDK가 실제 Observe 수집 API(v2)로 보낸 우회 호출이 break-glass 사용량 조회에 잡히는지 확인한다.
 * 키 형식 검사를 strict 모드로 켜서, break_glass_grant_id 키가 거부되지 않는다는 것도 함께 확인한다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-usage-ingestion;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false",
		"token-pilot.observe.ingestion.enabled=true",
		"token-pilot.ingestion.strict-metadata=true"
})
class BreakGlassUsageIngestionTest {

	private static final String KEY = "tp_break_glass_usage_secret";
	// 수집 이벤트의 occurredAt(2026-10-01T00:00Z)을 사이에 두도록 발급한다.
	private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 30, 23, 50);

	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;
	@Autowired IngestionRequestSizeFilter sizeFilter;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository keys;
	@Autowired UsageLogRepository usage;
	@Autowired UsageAccountingValueRepository values;
	@Autowired MemberRepository members;
	@Autowired BreakGlassGrantRepository grants;
	@Autowired OperationalEventRepository operationalEvents;
	@Autowired BreakGlassService breakGlassService;
	@Autowired BreakGlassUsageService usageService;
	@Autowired PasswordEncoder encoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;
	MockMvc mvc;
	Authentication operator;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sizeFilter, securityFilterChain).build();
		projects.saveAndFlush(Project.builder().id("bg-project").organizationId("org-1").projectKey("alpha")
				.name("alpha").status(ProjectStatus.ACTIVE).build());
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId("org-1").projectId("bg-project")
				.environment("prod").build());
		keys.saveAndFlush(ProjectApiKey.builder().id("bg-key").organizationId("org-1").projectId("bg-project")
				.environment("prod").name("bg-key").keyPrefix(KEY).keyHash(encoder.encode(KEY)).status("ACTIVE").build());
		members.saveAndFlush(Member.builder().id("operator").email("operator@example.test").name("operator")
				.provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				"operator", "org-1", "operator", "OPERATOR", ISSUED_AT, ISSUED_AT);
		operator = jwt.getAuthentication(jwt.createToken("operator@example.test", "USER"));
	}

	/** 수집 원장 writer가 REQUIRES_NEW로 커밋하므로 직접 정리한다. */
	@AfterEach
	void tearDown() {
		values.deleteAll();
		usage.deleteAll();
		operationalEvents.deleteAll();
		grants.deleteAll();
		jdbc.update("delete from organization_members");
		members.deleteAll();
		keys.deleteAll();
		environments.deleteAll();
		projects.deleteAll();
	}

	@Test
	void bypassedCallSentThroughObserveIngestionIsCounted() throws Exception {
		String grantId = breakGlassService.issue(operator,
				new BreakGlassIssueRequest("bg-project", "prod", "provider 장애 대응", 60), ISSUED_AT).grantId();

		send("evt-bypass", "{\"" + BreakGlassGrant.USAGE_METADATA_KEY + "\":\"" + grantId + "\"}");
		send("evt-normal", "{\"feature\":\"chat\"}");

		BreakGlassUsageResponse result = usageService.getUsage(operator, grantId, ISSUED_AT.plusMinutes(30));

		assertThat(result.withinWindow().events()).isEqualTo(1);
		assertThat(result.withinWindow().totalTokens()).isEqualTo(15);
		assertThat(result.withinWindow().callStatusCounts()).containsEntry("SUCCEEDED", 1L);
		assertThat(result.outsideWindow().events()).isZero();
	}

	private void send(String eventId, String metadataJson) throws Exception {
		String body = """
				{"projectKey":"alpha","environment":"prod","eventId":"%s","schemaVersion":"observe-v1",
				 "tokenSemantics":"INCLUSIVE_V1","provider":"openai","model":"m","callStatus":"SUCCEEDED",
				 "promptTokens":10,"completionTokens":5,"occurredAt":"2026-10-01T00:00:00Z","metadata":%s}
				""".formatted(eventId, metadataJson);
		mvc.perform(post("/api/v2/observe/events").header("X-API-Key", KEY)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());
	}
}
