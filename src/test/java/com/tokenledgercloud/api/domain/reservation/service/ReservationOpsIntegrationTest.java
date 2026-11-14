package com.tokenledgercloud.api.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.dto.ReconcileRequest;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementView;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * 예약 운영 API: 조회, 운영자 수동 정산, 권한. 실제 JWT 인증과 정책 API와 같은 조직 권한 확인을 쓴다.
 */
// 설정을 따로 주지 않아 다른 기본 @SpringBootTest와 같은 캐시 컨텍스트를 쓴다. (컨텍스트가 늘면 전체 테스트가 메모리 부족)
@SpringBootTest
class ReservationOpsIntegrationTest {

	private static final String ORG = "ops-org";
	private static final String PROJECT = "ops-project";
	private static final Instant OCCURRED = Instant.parse("2026-11-13T09:00:00Z");

	@Autowired ReservationService reservations;
	@Autowired ReservationQueryService query;
	@Autowired ReservationOpsService ops;
	@Autowired BudgetReservationRepository reservationRepository;
	@Autowired BudgetReservationCounterRepository counterRepository;
	@Autowired BudgetReservationSettlementRepository settlementRepository;
	@Autowired ProjectRepository projects;
	@Autowired MemberRepository members;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;
	@Autowired WebApplicationContext web;
	@Autowired FilterChainProxy filters;

	@BeforeEach
	void seed() {
		cleanUp();
		project(PROJECT, ORG);
		project("ops-foreign", "ops-other");
		member("ops-admin", ORG, "ADMIN");
		member("ops-viewer", ORG, "VIEWER");
		member("ops-outsider", "ops-other", "ADMIN");
	}

	// 이 클래스가 만든 행만 지운다. (같은 컨텍스트를 쓰는 다른 테스트의 데이터는 건드리지 않는다)
	@AfterEach
	void cleanUp() {
		settlementRepository.deleteAll();
		reservationRepository.deleteAll();
		counterRepository.deleteAll();
		jdbc.update("delete from organization_members where organization_id in ('ops-org', 'ops-other')");
		jdbc.update("delete from users where id in ('ops-admin', 'ops-viewer', 'ops-outsider')");
		jdbc.update("delete from projects where id in ('ops-project', 'ops-foreign')");
	}

	private void project(String id, String org) {
		projects.saveAndFlush(Project.builder().id(id).organizationId(org).projectKey(id).name(id)
				.status(ProjectStatus.ACTIVE).build());
	}

	private void member(String id, String org, String role) {
		members.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id).provider("local")
				.role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at)"
				+ " values(?,?,?,?,?,?)", id, org, id, role, LocalDateTime.now(), LocalDateTime.now());
	}

	private Authentication as(String memberId) {
		return jwt.getAuthentication(jwt.createToken(memberId + "@example.test", "USER"));
	}

	// 예약 하나를 만들고 id를 돌려준다. 만료는 하루 뒤라 회수 대상이 아니다.
	private String reserve(String requestId, String cost) {
		return reservations.reserve(new ReservationCommand(ORG, PROJECT, "prod", "2026-11", "dec:" + requestId,
				requestId, "openai", "gpt-test", 1000L, 500L, new BigDecimal(cost), null, new BigDecimal("10"), null,
				LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).reservation().getId();
	}

	// SDK처럼 클라이언트가 보내는 정산.
	private void clientSettle(String id, String requestId, SettlementAction action, String cost) {
		boolean commit = action == SettlementAction.COMMIT;
		reservations.settle(new SettlementCommand(id, PROJECT, "stl:" + id + ":" + action, requestId, action,
				commit ? new SettlementCommand.Usage(900L, 400L, 0L, 0L) : null,
				commit ? SettlementCommand.Cost.total(new BigDecimal(cost)) : null, OCCURRED));
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@Test
	void viewerSeesOpenReservationsByDefaultAndOthersByState() {
		String open = reserve("ops-req-1", "0.20");
		String settled = reserve("ops-req-2", "0.20");
		clientSettle(settled, "ops-req-2", SettlementAction.COMMIT, "0.10");

		// 기본은 아직 예산을 잡고 있는 예약만.
		assertThat(query.list(as("ops-viewer"), PROJECT, Set.of(), 50))
				.extracting(view -> view.reservationId()).containsExactly(open);
		assertThat(query.list(as("ops-viewer"), PROJECT, EnumSet.of(ReservationState.COMMITTED), 50))
				.singleElement().satisfies(view -> {
					assertThat(view.reservationId()).isEqualTo(settled);
					assertThat(view.committedCostUsd()).isEqualByComparingTo("0.10");
				});
	}

	@Test
	void otherOrganizationsCannotSeeOrSettleTheProject() {
		String id = reserve("ops-req-1", "0.20");

		assertError(() -> query.list(as("ops-outsider"), PROJECT, Set.of(), 50), ErrorCode.NOT_FOUND);
		assertError(() -> query.detail(as("ops-outsider"), PROJECT, id), ErrorCode.NOT_FOUND);
		assertError(() -> ops.reconcile(as("ops-outsider"), PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, "check")), ErrorCode.NOT_FOUND);
		// 같은 조직이라도 다른 프로젝트 경로로 예약 id를 넣으면 없는 것으로 본다.
		assertError(() -> query.detail(as("ops-admin"), "ops-foreign", id), ErrorCode.NOT_FOUND);
	}

	@Test
	void viewerCannotSettle() {
		String id = reserve("ops-req-1", "0.20");

		assertError(() -> ops.reconcile(as("ops-viewer"), PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, "check")), ErrorCode.FORBIDDEN);
		assertThat(reservationRepository.findById(id).orElseThrow().getState()).isEqualTo(ReservationState.RESERVED);
	}

	@Test
	void adminWritesOffAnUnknownResultAtTheReservedAmountAndTheHistoryShowsWhoClosedIt() {
		String id = reserve("ops-req-1", "0.20");
		clientSettle(id, "ops-req-1", SettlementAction.RECONCILIATION_REQUIRED, null);

		SettlementResult result = ops.reconcile(as("ops-admin"), PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, "Provider invoice never arrived"));

		assertThat(result.state()).isEqualTo(ReservationState.WRITTEN_OFF);
		assertThat(result.committedCostUsd()).isEqualByComparingTo("0.20");
		List<SettlementView> history = query.detail(as("ops-viewer"), PROJECT, id).settlements();
		assertThat(history).extracting(SettlementView::source).containsExactly("CLIENT", "OPERATOR");
		assertThat(history).extracting(SettlementView::resultingState)
				.containsExactly(ReservationState.RECONCILIATION_REQUIRED, ReservationState.WRITTEN_OFF);
	}

	@Test
	void adminCommitsTheInvoiceAmountAndARetryReturnsTheSameResult() {
		String id = reserve("ops-req-1", "0.20");
		clientSettle(id, "ops-req-1", SettlementAction.RECONCILIATION_REQUIRED, null);
		ReconcileRequest request = new ReconcileRequest(SettlementAction.COMMIT, new BigDecimal("0.17"), OCCURRED,
				"Matched provider invoice INV-1");

		SettlementResult first = ops.reconcile(as("ops-admin"), PROJECT, id, "ops-key-0001", request);
		SettlementResult retry = ops.reconcile(as("ops-admin"), PROJECT, id, "ops-key-0001", request);

		assertThat(first.state()).isEqualTo(ReservationState.COMMITTED);
		assertThat(first.committedCostUsd()).isEqualByComparingTo("0.17");
		assertThat(retry.duplicate()).isTrue();
		var counter = counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, "prod", "2026-11")
				.orElseThrow();
		assertThat(counter.getHeldUsd()).isEqualByComparingTo("0");
		assertThat(counter.getCommittedUsd()).isEqualByComparingTo("0.17");
		// 같은 키에 사건 시각만 다른 본문은 다른 정산이라 409다.
		assertError(() -> ops.reconcile(as("ops-admin"), PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.COMMIT, new BigDecimal("0.17"), OCCURRED.plusSeconds(1), "again")),
				ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void invalidManualSettlementsAreRejected() {
		String id = reserve("ops-req-1", "0.20");
		Authentication admin = as("ops-admin");

		// 운영자는 해제·미확인을 고를 수 없다. (SDK와 만료 회수의 몫)
		assertError(() -> ops.reconcile(admin, PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.RELEASE, null, OCCURRED, "x")), ErrorCode.INVALID_INPUT);
		assertError(() -> ops.reconcile(admin, PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.COMMIT, null, OCCURRED, "x")), ErrorCode.INVALID_INPUT);
		assertError(() -> ops.reconcile(admin, PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, BigDecimal.ONE, OCCURRED, "x")), ErrorCode.INVALID_INPUT);
		assertError(() -> ops.reconcile(admin, PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, " ")), ErrorCode.INVALID_INPUT);
		assertError(() -> ops.reconcile(admin, PROJECT, id, "short",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, "x")), ErrorCode.INVALID_INPUT);
		// RESERVED에서 바로 WRITE_OFF는 상태 전이 규칙에 없다. (미확인을 거쳐야 한다)
		assertError(() -> ops.reconcile(admin, PROJECT, id, "ops-key-0001",
				new ReconcileRequest(SettlementAction.WRITE_OFF, null, OCCURRED, "x")), ErrorCode.CONFLICT);
	}

	@Test
	void clientsCannotUseTheOperatorKeyPrefix() {
		String id = reserve("ops-req-1", "0.20");

		assertError(() -> reservations.settle(new SettlementCommand(id, PROJECT, "ops:pretend-operator", "ops-req-1",
				SettlementAction.RELEASE, null, null, OCCURRED)), ErrorCode.INVALID_INPUT);
	}

	@Test
	void httpRequiresLoginAndRejectsUnknownStatesWith400() throws Exception {
		reserve("ops-req-1", "0.20");
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(filters).build();
		String path = "/api/reservations/projects/" + PROJECT;

		mvc.perform(get(path)).andExpect(status().isUnauthorized());
		mvc.perform(get(path).header("Authorization", "Bearer " + jwt.createToken("ops-viewer@example.test", "USER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1));
		mvc.perform(get(path).param("state", "NOT_A_STATE")
				.header("Authorization", "Bearer " + jwt.createToken("ops-viewer@example.test", "USER")))
				.andExpect(status().isBadRequest());
	}
}
