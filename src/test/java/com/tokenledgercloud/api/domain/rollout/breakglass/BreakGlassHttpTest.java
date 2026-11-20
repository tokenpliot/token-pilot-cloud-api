package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * 실제 보안 필터와 JWT를 거쳐 고객 운영자가 break-glass를 실행하고 감사 기록을 확인하는 흐름. (이슈 #19 완료 기준)
 * H2 + Hibernate 스키마 생성, Flyway는 끈다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-http;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
class BreakGlassHttpTest {

	private static final String BASE = "/api/rollout/break-glass";
	private static final String ISSUE_BODY = """
			{"projectId": "project-a", "environment": "prod", "reason": "provider 장애로 Enforce 우회", "durationMinutes": 30}
			""";

	@Autowired WebApplicationContext web;
	@Autowired FilterChainProxy filters;
	@Autowired JwtTokenProvider jwt;
	@Autowired JdbcTemplate jdbc;
	@Autowired ProjectRepository projectRepository;
	@Autowired ProjectEnvironmentRepository environmentRepository;
	@Autowired MemberRepository memberRepository;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		for (String table : List.of("break_glass_grants", "operational_events", "organization_members",
				"project_environments", "projects", "users")) {
			jdbc.update("delete from " + table);
		}
		projectRepository.saveAndFlush(Project.builder().id("project-a").organizationId("org-1")
				.projectKey("alpha").name("alpha").status(ProjectStatus.ACTIVE).build());
		environmentRepository.saveAndFlush(ProjectEnvironment.builder()
				.organizationId("org-1").projectId("project-a").environment("prod").build());
		member("operator", "OPERATOR");
		member("viewer", "VIEWER");
		mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(filters).build();
	}

	private void member(String id, String role) {
		memberRepository.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id)
				.provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				id, "org-1", id, role, LocalDateTime.now(), LocalDateTime.now());
	}

	private String bearer(String id) {
		return "Bearer " + jwt.createToken(id + "@example.test", "USER");
	}

	@Test
	void operatorRunsBreakGlassAndAuditTrailIsVisible() throws Exception {
		// 1. 운영자가 발급한다.
		String body = mvc.perform(post(BASE).header("Authorization", bearer("operator"))
						.contentType(MediaType.APPLICATION_JSON).content(ISSUE_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.issuedBy").value("operator"))
				.andReturn().getResponse().getContentAsString();
		String grantId = JsonPath.read(body, "$.data.grantId");

		// 2. SDK는 로그인 없이 유효 여부를 확인한다.
		mvc.perform(get(BASE + "/active").param("projectId", "project-a").param("environment", "prod"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.active").value(true))
				.andExpect(jsonPath("$.data.grant.grantId").value(grantId));

		// 3. 운영자가 취소한다.
		mvc.perform(post(BASE + "/" + grantId + "/revoke").header("Authorization", bearer("operator"))
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason": "provider 복구 확인"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REVOKED"));

		// 4. 조회 권한만 있는 사람도 사후 감사 기록을 볼 수 있다.
		mvc.perform(get(BASE).param("projectId", "project-a").header("Authorization", bearer("viewer")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].grantId").value(grantId))
				.andExpect(jsonPath("$.data[0].reason").value("provider 장애로 Enforce 우회"))
				.andExpect(jsonPath("$.data[0].revokedBy").value("operator"))
				.andExpect(jsonPath("$.data[0].revokeReason").value("provider 복구 확인"));
		mvc.perform(get("/api/events").param("projectId", "project-a"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForList("select event_code from operational_events order by occurred_at", String.class))
				.containsExactly("BREAK_GLASS_ISSUED", "BREAK_GLASS_REVOKED");
	}

	@Test
	void anonymousAndViewerCannotIssue() throws Exception {
		mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(ISSUE_BODY))
				.andExpect(status().isUnauthorized());
		mvc.perform(post(BASE).header("Authorization", bearer("viewer"))
						.contentType(MediaType.APPLICATION_JSON).content(ISSUE_BODY))
				.andExpect(status().isForbidden());
		mvc.perform(get(BASE).param("projectId", "project-a"))
				.andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("select count(*) from break_glass_grants", Integer.class)).isZero();
	}
}
