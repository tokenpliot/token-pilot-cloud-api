package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import com.tokenledgercloud.api.TokenLedgerCloudApplication;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassActiveResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassGrantResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * 재시작 테스트(이슈 #19 완료 기준 "재시작 결과가 파일럿 보고서에 남는다").
 * break-glass를 발급·취소한 뒤 같은 DB로 두 번째 애플리케이션을 띄운다(메모리에서 넘어가는 것 없음).
 * 재시작한 서버도 유효 여부·만료·취소·범위당 1개 규칙·우회 사용량을 그대로 판단해야 한다.
 */
// BreakGlassUsageServiceTest와 같은 설정이라 같은 캐시 컨텍스트를 쓴다. (컨텍스트가 늘면 전체 테스트가 메모리 부족)
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-usage;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
class BreakGlassRestartTest {

	private static final String ORG = "org-restart";
	private static final String PROJECT = "project-restart";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

	@Autowired BreakGlassService service;
	@Autowired ProjectRepository projectRepository;
	@Autowired ProjectEnvironmentRepository environmentRepository;
	@Autowired MemberRepository memberRepository;
	@Autowired UsageLogRepository usageLogRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;
	@Autowired DataSource dataSource;

	private Authentication operator;

	@BeforeEach
	void setUp() {
		cleanTables();
		projectRepository.saveAndFlush(Project.builder().id(PROJECT).organizationId(ORG)
				.projectKey("key-restart").name("restart").status(ProjectStatus.ACTIVE).build());
		for (String environment : List.of("prod", "staging")) {
			environmentRepository.saveAndFlush(ProjectEnvironment.builder()
					.organizationId(ORG).projectId(PROJECT).environment(environment).build());
		}
		memberRepository.saveAndFlush(Member.builder().id("restart-operator").email("restart-operator@example.test")
				.name("restart-operator").provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				"m-restart-operator", ORG, "restart-operator", "OPERATOR", NOW, NOW);
		operator = jwt.getAuthentication(jwt.createToken("restart-operator@example.test", "USER"));
	}

	// 이 테스트는 트랜잭션으로 되돌리지 않고 커밋한다(두 번째 서버가 봐야 하므로). 직접 정리한다.
	@AfterEach
	void cleanTables() {
		jdbc.update("delete from usage_events where project_id = ?", PROJECT);
		jdbc.update("delete from operational_events where project_id = ?", PROJECT);
		jdbc.update("delete from break_glass_grants where project_id = ?", PROJECT);
		jdbc.update("delete from organization_members where organization_id = ?", ORG);
		jdbc.update("delete from users where id = ?", "restart-operator");
		jdbc.update("delete from project_environments where project_id = ?", PROJECT);
		jdbc.update("delete from projects where id = ?", PROJECT);
	}

	@Test
	void restartedServerKeepsGrantStateScopeRuleAndUsage() throws Exception {
		// 재시작 전: prod는 유효한 grant, staging은 발급 후 취소한 grant. prod 우회 호출 1건.
		BreakGlassGrantResponse prod = service.issue(operator,
				new BreakGlassIssueRequest(PROJECT, "prod", "provider 장애 대응", 60), NOW);
		BreakGlassGrantResponse staging = service.issue(operator,
				new BreakGlassIssueRequest(PROJECT, "staging", "점검", 60), NOW);
		service.revoke(operator, staging.grantId(), new BreakGlassRevokeRequest("점검 종료"), NOW.plusMinutes(5));
		usageLogRepository.saveAndFlush(UsageLog.builder()
				.organizationId(ORG).projectId(PROJECT).environment("prod")
				.requestId("req-restart").eventId("evt-restart")
				.provider("openai").model("m").promptTokens(100L).completionTokens(0L).pricingVersion("v1")
				.metadataJson("{\"" + BreakGlassGrant.USAGE_METADATA_KEY + "\":\"" + prod.grantId() + "\"}")
				.accountingStatus("PENDING").callStatus("SUCCEEDED")
				.occurredAt(NOW.plusMinutes(10)).build());

		// 같은 DB로 서버를 다시 띄운다. 테이블을 지우거나 새로 만들지 않는다.
		try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(TokenLedgerCloudApplication.class)
				.properties(
						"spring.datasource.url=" + databaseUrl(),
						"spring.datasource.username=sa",
						"spring.datasource.password=",
						"spring.jpa.hibernate.ddl-auto=none",
						"spring.jpa.show-sql=false",
						"spring.flyway.enabled=false",
						"server.port=0")
				.run()) {
			BreakGlassService afterRestart = restarted.getBean(BreakGlassService.class);
			BreakGlassUsageService usageAfterRestart = restarted.getBean(BreakGlassUsageService.class);
			Authentication operatorAfterRestart = restarted.getBean(JwtTokenProvider.class)
					.getAuthentication(jwt.createToken("restart-operator@example.test", "USER"));

			// 유효한 grant는 재시작 뒤에도 같은 grant로 유효하고, 만료 시각이 지나면 배치 없이 무효다.
			BreakGlassActiveResponse active = afterRestart.getActive(PROJECT, "prod", NOW.plusMinutes(30));
			assertThat(active.active()).isTrue();
			assertThat(active.grant().grantId()).isEqualTo(prod.grantId());
			assertThat(afterRestart.getActive(PROJECT, "prod", NOW.plusMinutes(60)).active()).isFalse();

			// 취소된 grant는 재시작 뒤에도 취소 상태다.
			assertThat(afterRestart.getActive(PROJECT, "staging", NOW.plusMinutes(30)).active()).isFalse();
			assertThat(afterRestart.getHistory(operatorAfterRestart, PROJECT, "staging", null, NOW.plusMinutes(30)))
					.singleElement()
					.satisfies(grant -> assertThat(grant.status()).isEqualTo(BreakGlassStatus.REVOKED));

			// 범위당 유효한 grant 하나 규칙도 DB에 있으므로 재시작 뒤에도 지켜진다.
			assertThatThrownBy(() -> afterRestart.issue(operatorAfterRestart,
					new BreakGlassIssueRequest(PROJECT, "prod", "중복 발급 시도", 30), NOW.plusMinutes(30)))
					.isInstanceOf(ApiException.class)
					.extracting(e -> ((ApiException) e).getErrorCode())
					.isEqualTo(ErrorCode.CONFLICT);

			// 재시작 전에 기록된 우회 호출도 그대로 집계된다.
			BreakGlassUsageResponse usage = usageAfterRestart.getUsage(operatorAfterRestart, prod.grantId(),
					NOW.plusMinutes(30));
			assertThat(usage.withinWindow().events()).isEqualTo(1);
		}
	}

	// 지금 테스트 컨텍스트가 쓰는 인메모리 DB 주소. 두 번째 애플리케이션이 같은 DB에 붙게 한다.
	private String databaseUrl() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			return connection.getMetaData().getURL();
		}
	}
}
