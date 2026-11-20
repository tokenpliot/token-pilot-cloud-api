package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * 여러 운영자가 같은 범위에 동시에 발급·취소해도 유효한 grant가 하나만 남는지 확인한다.
 * 각 요청이 자기 트랜잭션으로 커밋돼야 하므로 클래스에 @Transactional을 붙이지 않고, 앞뒤로 직접 지운다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-concurrency;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
class BreakGlassConcurrencyTest {

	private static final String ORG = "org-1";
	private static final String PROJECT = "project-a";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);
	private static final int THREADS = 8;

	@Autowired BreakGlassService service;
	@Autowired BreakGlassGrantRepository grantRepository;
	@Autowired ProjectRepository projectRepository;
	@Autowired ProjectEnvironmentRepository environmentRepository;
	@Autowired MemberRepository memberRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;

	// 같은 조직의 운영자 THREADS명.
	private final List<Authentication> operators = new ArrayList<>();

	@BeforeEach
	void setUp() {
		cleanUp();
		projectRepository.saveAndFlush(Project.builder().id(PROJECT).organizationId(ORG)
				.projectKey("key-a").name("a").status(ProjectStatus.ACTIVE).build());
		environmentRepository.saveAndFlush(ProjectEnvironment.builder()
				.organizationId(ORG).projectId(PROJECT).environment("prod").build());
		for (int i = 0; i < THREADS; i++) {
			String id = "ops" + i;
			memberRepository.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id)
					.provider("local").role(Role.USER).build());
			jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
					id, ORG, id, "OPERATOR", NOW, NOW);
			operators.add(jwt.getAuthentication(jwt.createToken(id + "@example.test", "USER")));
		}
	}

	@AfterEach
	void cleanUp() {
		for (String table : List.of("break_glass_grants", "operational_events", "organization_members",
				"project_environments", "projects", "users")) {
			jdbc.update("delete from " + table);
		}
	}

	// 모든 작업을 같은 순간에 출발시키고, 각 작업이 성공했는지(true) CONFLICT로 거절됐는지(false) 모은다.
	private List<Boolean> runTogether(List<Callable<Object>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (Callable<Object> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					try {
						task.call();
						return true;
					} catch (ApiException exception) {
						assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
						return false;
					}
				}));
			}
			start.countDown();
			List<Boolean> results = new ArrayList<>();
			for (Future<Boolean> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private long revokedEvents() {
		return jdbc.queryForObject("select count(*) from operational_events where event_code = 'BREAK_GLASS_REVOKED'", Long.class);
	}

	@Test
	void concurrentIssuesOnSameScopeLetOnlyOneSucceed() throws Exception {
		List<Callable<Object>> tasks = new ArrayList<>();
		for (Authentication operator : operators) {
			tasks.add(() -> service.issue(operator, new BreakGlassIssueRequest(PROJECT, "prod", "동시 발급", 30), NOW));
		}

		List<Boolean> results = runTogether(tasks);

		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(grantRepository.findAll()).hasSize(1);
		assertThat(service.getActive(PROJECT, "prod", NOW).active()).isTrue();
	}

	@Test
	void concurrentReissuesAfterExpiryLetOnlyOneSucceed() throws Exception {
		service.issue(operators.get(0), new BreakGlassIssueRequest(PROJECT, "prod", "첫 발급", 10), NOW);
		LocalDateTime later = NOW.plusMinutes(20);
		List<Callable<Object>> tasks = new ArrayList<>();
		for (Authentication operator : operators) {
			tasks.add(() -> service.issue(operator, new BreakGlassIssueRequest(PROJECT, "prod", "재발급", 30), later));
		}

		List<Boolean> results = runTogether(tasks);

		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		// 만료된 첫 grant + 새 grant 하나.
		assertThat(grantRepository.findAll()).hasSize(2);
		assertThat(grantRepository.findAll()).filteredOn(grant -> grant.getActiveScopeKey() != null).hasSize(1);
	}

	@Test
	void concurrentRevokesOfSameGrantLetOnlyOneSucceed() throws Exception {
		String grantId = service.issue(operators.get(0),
				new BreakGlassIssueRequest(PROJECT, "prod", "발급", 30), NOW).grantId();
		List<Callable<Object>> tasks = new ArrayList<>();
		for (Authentication operator : operators) {
			tasks.add(() -> service.revoke(operator, grantId, new BreakGlassRevokeRequest("동시 취소"), NOW.plusMinutes(1)));
		}

		List<Boolean> results = runTogether(tasks);

		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		BreakGlassGrant revoked = grantRepository.findById(grantId).orElseThrow();
		assertThat(revoked.statusAt(NOW.plusMinutes(1))).isEqualTo(BreakGlassStatus.REVOKED);
		// 취소 감사 이벤트도 한 번만 남는다.
		assertThat(revokedEvents()).isEqualTo(1);
	}
}
