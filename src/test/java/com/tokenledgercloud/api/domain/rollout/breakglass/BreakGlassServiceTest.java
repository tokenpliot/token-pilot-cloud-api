package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.event.entity.OperationalEvent;
import com.tokenledgercloud.api.domain.event.repository.OperationalEventRepository;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassGrantResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * break-glass 발급·만료·취소·감사 이력 규칙과 역할별 권한을 확인한다.
 * 인증은 실제 JWT로 만들고 조직 권한은 #14의 PolicyAccess를 그대로 거친다. H2 + Hibernate 스키마 생성, Flyway는 끈다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-service;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
@Transactional
class BreakGlassServiceTest {

	private static final String ORG = "org-1";
	private static final String PROJECT = "project-a";
	private static final String OTHER_ORG_PROJECT = "project-b";
	// 모든 테스트의 기준 시각(UTC).
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

	@Autowired BreakGlassService service;
	@Autowired ProjectRepository projectRepository;
	@Autowired ProjectEnvironmentRepository environmentRepository;
	@Autowired MemberRepository memberRepository;
	@Autowired OperationalEventRepository eventRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;

	private Authentication operator;
	private Authentication admin;
	private Authentication viewer;
	private Authentication outsider;

	@BeforeEach
	void setUp() {
		project(PROJECT, ORG, "prod", "staging");
		project(OTHER_ORG_PROJECT, "org-2", "prod");
		operator = member("operator", ORG, "OPERATOR");
		admin = member("admin", ORG, "ADMIN");
		viewer = member("viewer", ORG, "VIEWER");
		outsider = member("outsider", "org-2", "OWNER");
	}

	private void project(String id, String organizationId, String... environments) {
		projectRepository.saveAndFlush(Project.builder().id(id).organizationId(organizationId)
				.projectKey("key-" + id).name(id).status(ProjectStatus.ACTIVE).build());
		for (String environment : environments) {
			environmentRepository.saveAndFlush(ProjectEnvironment.builder()
					.organizationId(organizationId).projectId(id).environment(environment).build());
		}
	}

	// 회원과 조직 멤버십을 만들고, 그 회원으로 로그인한 인증 정보를 돌려주는 도우미. (PolicyIntegrationTest와 같은 방식)
	private Authentication member(String id, String organizationId, String role) {
		memberRepository.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id)
				.provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				id, organizationId, id, role, NOW, NOW);
		return jwt.getAuthentication(jwt.createToken(id + "@example.test", "USER"));
	}

	private BreakGlassGrantResponse issue(Authentication who, String environment, int minutes, LocalDateTime at) {
		return service.issue(who, new BreakGlassIssueRequest(PROJECT, environment, "provider 장애 대응", minutes), at);
	}

	private static void assertError(ThrowingCallable call, ErrorCode expected) {
		assertThatThrownBy(call)
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(expected);
	}

	@Test
	void issuedGrantIsActiveUntilExpiryAndKeepsAuditFields() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);

		assertThat(grant.status()).isEqualTo(BreakGlassStatus.ACTIVE);
		// 실행자는 #14 정책 감사와 같이 회원 id로 남는다.
		assertThat(grant.issuedBy()).isEqualTo("operator");
		assertThat(grant.reason()).isEqualTo("provider 장애 대응");
		assertThat(grant.expiresAt().toLocalDateTime()).isEqualTo(NOW.plusMinutes(30));

		assertThat(service.getActive(PROJECT, "prod", NOW.plusMinutes(29)).active()).isTrue();
	}

	@Test
	void grantStopsBeingActiveAtExpiryWithoutAnyJob() {
		issue(operator, "prod", 30, NOW);

		// 만료 시각과 같은 순간부터 무효다.
		var active = service.getActive(PROJECT, "prod", NOW.plusMinutes(30));

		assertThat(active.active()).isFalse();
		assertThat(active.grant()).isNull();
	}

	@Test
	void cannotIssueWhileScopeHasActiveGrant() {
		issue(operator, "prod", 30, NOW);

		assertError(() -> issue(admin, "prod", 60, NOW.plusMinutes(10)), ErrorCode.CONFLICT);
	}

	@Test
	void otherEnvironmentGetsItsOwnGrant() {
		issue(operator, "prod", 30, NOW);

		BreakGlassGrantResponse staging = issue(operator, "staging", 30, NOW);

		assertThat(staging.status()).isEqualTo(BreakGlassStatus.ACTIVE);
		assertThat(service.getActive(PROJECT, "prod", NOW).active()).isTrue();
	}

	@Test
	void expiredScopeCanBeReissuedAndKeepsExpiredRecord() {
		BreakGlassGrantResponse first = issue(operator, "prod", 30, NOW);

		BreakGlassGrantResponse second = issue(operator, "prod", 30, NOW.plusHours(1));

		assertThat(second.grantId()).isNotEqualTo(first.grantId());
		List<BreakGlassGrantResponse> history = service.getHistory(viewer, PROJECT, "prod", null, NOW.plusHours(1));
		assertThat(history).extracting(BreakGlassGrantResponse::status)
				.containsExactly(BreakGlassStatus.ACTIVE, BreakGlassStatus.EXPIRED);
	}

	@Test
	void revokeEndsGrantImmediatelyAndRecordsWhoAndWhy() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 60, NOW);

		BreakGlassGrantResponse revoked = service.revoke(admin, grant.grantId(),
				new BreakGlassRevokeRequest("장애 복구 완료"), NOW.plusMinutes(5));

		assertThat(revoked.status()).isEqualTo(BreakGlassStatus.REVOKED);
		assertThat(revoked.revokedBy()).isEqualTo("admin");
		assertThat(revoked.revokeReason()).isEqualTo("장애 복구 완료");
		assertThat(service.getActive(PROJECT, "prod", NOW.plusMinutes(6)).active()).isFalse();
		// 취소 직후 같은 범위에 다시 발급할 수 있다.
		assertThat(issue(operator, "prod", 10, NOW.plusMinutes(7)).status()).isEqualTo(BreakGlassStatus.ACTIVE);
	}

	@Test
	void cannotRevokeExpiredOrRevokedGrant() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);

		assertError(() -> service.revoke(operator, grant.grantId(),
				new BreakGlassRevokeRequest("늦은 취소"), NOW.plusMinutes(31)), ErrorCode.CONFLICT);
	}

	@Test
	void issueAndRevokeAreAlsoRecordedAsOperationalEvents() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);
		service.revoke(operator, grant.grantId(), new BreakGlassRevokeRequest("복구 완료"), NOW.plusMinutes(1));

		List<OperationalEvent> events = eventRepository.findAll().stream()
				.filter(event -> PROJECT.equals(event.getProjectId()))
				.toList();

		assertThat(events).extracting(OperationalEvent::getEventCode)
				.containsExactlyInAnyOrder("BREAK_GLASS_ISSUED", "BREAK_GLASS_REVOKED");
		assertThat(events).allSatisfy(event -> {
			assertThat(event.getOrganizationId()).isEqualTo(ORG);
			assertThat(event.getMetadataJson()).contains(grant.grantId());
		});
	}

	@Test
	void cannotIssueForUnknownEnvironment() {
		assertError(() -> issue(operator, "dev", 30, NOW), ErrorCode.NOT_FOUND);
	}

	@Test
	void historyWithoutEnvironmentCoversWholeProjectNewestFirst() {
		issue(operator, "prod", 30, NOW);
		issue(operator, "staging", 30, NOW.plusMinutes(1));

		List<BreakGlassGrantResponse> all = service.getHistory(viewer, PROJECT, null, null, NOW.plusMinutes(2));
		List<BreakGlassGrantResponse> limited = service.getHistory(viewer, PROJECT, null, 1, NOW.plusMinutes(2));

		assertThat(all).extracting(BreakGlassGrantResponse::environment).containsExactly("staging", "prod");
		assertThat(limited).hasSize(1);
		assertError(() -> service.getHistory(viewer, PROJECT, null, 0, NOW), ErrorCode.INVALID_INPUT);
	}

	// --- 역할별 권한 (A안: OWNER/ADMIN/OPERATOR 발급·취소, VIEWER 조회만) ---

	@Test
	void adminCanIssueAndOperatorCanRevokeIt() {
		BreakGlassGrantResponse grant = issue(admin, "prod", 30, NOW);

		assertThat(service.revoke(operator, grant.grantId(), new BreakGlassRevokeRequest("복구"), NOW.plusMinutes(1))
				.status()).isEqualTo(BreakGlassStatus.REVOKED);
	}

	@Test
	void viewerCanReadHistoryButCannotIssueOrRevoke() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);

		assertError(() -> issue(viewer, "staging", 30, NOW), ErrorCode.FORBIDDEN);
		assertError(() -> service.revoke(viewer, grant.grantId(), new BreakGlassRevokeRequest("사유"), NOW), ErrorCode.FORBIDDEN);
		assertThat(service.getHistory(viewer, PROJECT, "prod", null, NOW)).hasSize(1);
	}

	@Test
	void memberOfOtherOrganizationSeesNotFoundEverywhere() {
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);

		// 다른 조직의 OWNER여도 이 프로젝트에는 권한이 없고, 존재 여부도 드러나지 않는다.
		assertError(() -> issue(outsider, "prod", 30, NOW), ErrorCode.NOT_FOUND);
		assertError(() -> service.revoke(outsider, grant.grantId(), new BreakGlassRevokeRequest("사유"), NOW), ErrorCode.NOT_FOUND);
		assertError(() -> service.getHistory(outsider, PROJECT, null, null, NOW), ErrorCode.NOT_FOUND);
		// 반대로 이 조직의 운영자도 다른 조직 프로젝트에는 발급할 수 없다.
		assertError(() -> service.issue(operator, new BreakGlassIssueRequest(OTHER_ORG_PROJECT, "prod", "사유", 30), NOW),
				ErrorCode.NOT_FOUND);
	}

	@Test
	void anonymousCannotIssueRevokeOrReadHistory() {
		Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
		BreakGlassGrantResponse grant = issue(operator, "prod", 30, NOW);

		assertError(() -> issue(null, "staging", 30, NOW), ErrorCode.UNAUTHORIZED);
		assertError(() -> issue(anonymous, "staging", 30, NOW), ErrorCode.UNAUTHORIZED);
		assertError(() -> service.revoke(anonymous, grant.grantId(), new BreakGlassRevokeRequest("사유"), NOW),
				ErrorCode.UNAUTHORIZED);
		assertError(() -> service.getHistory(null, PROJECT, null, null, NOW), ErrorCode.UNAUTHORIZED);
	}
}
