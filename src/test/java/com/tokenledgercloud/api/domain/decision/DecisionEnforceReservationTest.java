package com.tokenledgercloud.api.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tokenledgercloud.api.domain.ingestion.support.ProjectKeyFixtures;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.policy.PolicyRequests;
import com.tokenledgercloud.api.domain.policy.PolicyRules;
import com.tokenledgercloud.api.domain.policy.PolicyService;
import com.tokenledgercloud.api.domain.pricing.entity.PricingPlan;
import com.tokenledgercloud.api.domain.pricing.repository.PricingPlanRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.projectapikey.repository.ProjectApiKeyRepository;
import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.service.ReservationService;
import com.tokenledgercloud.api.domain.shadow.service.AdvisoryReplay;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * Enforce를 켠 판정 API가 ENFORCE + ALLOW에서 예산을 예약하고, 미정산 예약까지 더해 한도를 넘으면 DENY_BUDGET으로 바꾸는지 확인한다.
 * 정산 API·SDK와의 왕복은 token-pilot-client의 실서버 E2E가 확인한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:decision-enforce;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false",
	"token-pilot.observe.ingestion.rate-unit-tokens=1000000",
	"token-pilot.decision.enforce.enabled=true"
})
class DecisionEnforceReservationTest {

	private static final String MODEL = "gpt-priced";

	@Autowired DecisionService service;
	@Autowired ReservationService reservations;
	@Autowired DecisionRecordRepository records;
	@Autowired PolicyService policies;
	@Autowired ProjectRepository projects;
	@Autowired ProjectEnvironmentRepository environments;
	@Autowired ProjectApiKeyRepository apiKeys;
	@Autowired PricingPlanRepository pricingPlans;
	@Autowired MemberRepository members;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtTokenProvider jwt;
	@Autowired JdbcTemplate jdbc;

	Project project;
	String key;
	Authentication admin;

	@BeforeEach
	void setUp() {
		clean();
		ProjectKeyFixtures fixtures = new ProjectKeyFixtures(projects, apiKeys, passwordEncoder);
		project = fixtures.project("alpha", ProjectStatus.ACTIVE);
		environments.saveAndFlush(ProjectEnvironment.builder().organizationId(ProjectKeyFixtures.ORG)
			.projectId(project.getId()).environment("prod").build());
		key = fixtures.key(project, null, "ACTIVE", null).raw();
		members.saveAndFlush(Member.builder().id("admin").email("admin@example.test").name("admin").provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
			"m-admin", ProjectKeyFixtures.ORG, "admin", "ADMIN", LocalDateTime.now(), LocalDateTime.now());
		admin = jwt.getAuthentication(jwt.createToken("admin@example.test", "USER"));
		// 100만 토큰당 prompt 2, completion 8 -> 입력 1000, 출력 500이면 0.006 USD
		pricingPlans.saveAndFlush(PricingPlan.builder().id("plan-1").catalogId("catalog-1").provider("openai").model(MODEL)
			.currency("USD").promptRate(new BigDecimal("2")).completionRate(new BigDecimal("8")).version("prices-v1")
			.effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
	}

	@AfterEach
	void clean() {
		for (String table : List.of("budget_reservation_settlements", "budget_reservations", "budget_reservation_counters",
			"decision_records", "policy_request_contexts", "policy_snapshots", "customer_policies", "monthly_budget_settings",
			"pricing_snapshots", "pricing_plans", "organization_members", "project_api_keys", "project_environments",
			"projects", "users")) {
			jdbc.update("delete from " + table);
		}
	}

	private void policy(String hard, String requestHard) {
		policies.replace(admin, project.getId(), null, new PolicyRequests.Replace(0L, new PolicyRules("USD", null,
			new BigDecimal(hard), null, requestHard == null ? null : new BigDecimal(requestHard), true, List.of()), "test policy"));
	}

	private DecisionResponse decide(String requestId, EnforcementMode mode) {
		return service.decide(key, "dec:" + requestId, new DecisionCommand("2026-10-01", requestId, "prod", "openai", MODEL,
			mode, 1000, 500, null, null, Map.of()));
	}

	private long reservationRows() {
		return jdbc.queryForObject("select count(*) from budget_reservations", Long.class);
	}

	@Test
	void enforceAllowHoldsTheRequiredCostAndReplaysTheSameReservation() {
		policy("100", null);

		DecisionResponse first = decide("req-1", EnforcementMode.ENFORCE);
		DecisionResponse again = decide("req-1", EnforcementMode.ENFORCE);

		assertThat(first.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(first.effectiveMode()).isEqualTo(EnforcementMode.ENFORCE);
		assertThat(first.reservation()).isNotNull();
		assertThat(first.reservation().state()).isEqualTo(ReservationState.RESERVED);
		assertThat(first.reservation().reservedCostUsd()).isEqualTo(first.requiredCostUsd()).isEqualTo("0.006000");
		assertThat(Instant.parse(first.reservation().expiresAt())).isAfter(Instant.parse(first.evaluatedAt()));
		assertThat(again).isEqualTo(first);
		assertThat(reservationRows()).isEqualTo(1);
	}

	@Test
	void unsettledReservationsCountAgainstTheBudgetUntilReleased() {
		// 한도 0.010: 0.006짜리 하나는 들어가지만, 정산 전에는 두 번째가 들어갈 자리가 없다.
		policy("0.010", null);
		DecisionResponse held = decide("req-1", EnforcementMode.ENFORCE);

		DecisionResponse denied = decide("req-2", EnforcementMode.ENFORCE);

		assertThat(denied.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(denied.reasons()).extracting(DecisionResponse.Reason::code)
			.containsExactly(DecisionService.RESERVED_BUDGET_EXCEEDED);
		assertThat(denied.reservation()).isNull();
		assertThat(denied.retryAt()).isNull();
		// 예약 카운터는 그 순간의 동시 예약으로 정해져 Shadow가 재현할 수 없다. 기준선 불일치로 오인하지 않게 따로 표시한다.
		DecisionRecord deniedRecord = records.findByProjectIdAndIdempotencyKey(project.getId(), "dec:req-2").orElseThrow();
		assertThat(AdvisoryReplay.replay(deniedRecord, null, List.of(), null).status()).isEqualTo("RESERVATION_NOT_REPLAYABLE");

		reservations.settle(new SettlementCommand(held.reservation().reservationId(), project.getId(),
			"stl:" + held.reservation().reservationId() + ":RELEASE", "req-1", SettlementAction.RELEASE, null, null,
			Instant.parse(held.evaluatedAt()), null));

		assertThat(decide("req-3", EnforcementMode.ENFORCE).outcome()).isEqualTo(DecisionOutcome.ALLOW);
	}

	@Test
	void aReservationClosedBeforeTheDecisionWasStoredIsNotReusedAsAllow() {
		policy("100", null);
		// 판정을 저장하기 전에 서버가 죽어 예약만 남았고, 그 사이 만료 회수가 예약을 풀었다.
		var orphan = reservations.reserve(new ReservationCommand(ProjectKeyFixtures.ORG, project.getId(), "prod",
			YearMonth.now(ZoneOffset.UTC).toString(), "dec:req-1", "req-1", "openai", MODEL, 1000L, 500L,
			new BigDecimal("0.006"), null, null, "ctx-crashed", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5))).reservation();
		reservations.settle(new SettlementCommand(orphan.getId(), project.getId(), "stl:" + orphan.getId() + ":RELEASE",
			"req-1", SettlementAction.RELEASE, null, null, Instant.now(), null));

		DecisionResponse retry = decide("req-1", EnforcementMode.ENFORCE);

		assertThat(retry.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
		assertThat(retry.reasons()).extracting(DecisionResponse.Reason::code).containsExactly(DecisionService.RESERVATION_CLOSED);
		assertThat(retry.reservation()).isNull();
	}

	@Test
	void enforceDenialHoldsNothing() {
		policy("100", "0.001");

		DecisionResponse denied = decide("req-deny", EnforcementMode.ENFORCE);

		assertThat(denied.outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
		assertThat(denied.effectiveMode()).isEqualTo(EnforcementMode.ENFORCE);
		assertThat(denied.reservation()).isNull();
		assertThat(reservationRows()).isZero();
	}

	@Test
	void advisoryAllowHoldsNothing() {
		policy("100", null);

		DecisionResponse advisory = decide("req-advisory", EnforcementMode.ADVISORY);

		assertThat(advisory.outcome()).isEqualTo(DecisionOutcome.ALLOW);
		assertThat(advisory.effectiveMode()).isEqualTo(EnforcementMode.ADVISORY);
		assertThat(advisory.reservation()).isNull();
		assertThat(reservationRows()).isZero();
	}
}
