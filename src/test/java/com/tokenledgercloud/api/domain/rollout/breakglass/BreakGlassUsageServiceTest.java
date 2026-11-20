package com.tokenledgercloud.api.domain.rollout.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
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
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.security.JwtTokenProvider;

/**
 * break-glass 우회 호출의 사용량·비용 집계 규칙을 확인한다.
 * 원장 행은 저장소로 직접 넣는다. 실제 수집 경로로 저장된 metadata 형식은 BreakGlassUsageIngestionTest가 확인한다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:break-glass-usage;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
@Transactional
class BreakGlassUsageServiceTest {

	private static final String ORG = "org-1";
	private static final String PROJECT = "project-a";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

	@Autowired BreakGlassService breakGlassService;
	@Autowired BreakGlassUsageService usageService;
	@Autowired ProjectRepository projectRepository;
	@Autowired ProjectEnvironmentRepository environmentRepository;
	@Autowired MemberRepository memberRepository;
	@Autowired UsageLogRepository usageLogRepository;
	@Autowired UsageAccountingValueRepository valueRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwt;

	private Authentication operator;
	private Authentication viewer;
	private Authentication outsider;
	private int sequence;

	@BeforeEach
	void setUp() {
		projectRepository.saveAndFlush(Project.builder().id(PROJECT).organizationId(ORG)
				.projectKey("key-a").name("a").status(ProjectStatus.ACTIVE).build());
		for (String environment : new String[] {"prod", "staging"}) {
			environmentRepository.saveAndFlush(ProjectEnvironment.builder()
					.organizationId(ORG).projectId(PROJECT).environment(environment).build());
		}
		projectRepository.saveAndFlush(Project.builder().id("project-b").organizationId("org-2")
				.projectKey("key-b").name("b").status(ProjectStatus.ACTIVE).build());
		operator = member("operator", ORG, "OPERATOR");
		viewer = member("viewer", ORG, "VIEWER");
		outsider = member("outsider", "org-2", "OWNER");
	}

	private Authentication member(String id, String organizationId, String role) {
		memberRepository.saveAndFlush(Member.builder().id(id).email(id + "@example.test").name(id)
				.provider("local").role(Role.USER).build());
		jdbc.update("insert into organization_members(id, organization_id, user_id, role, created_at, updated_at) values(?,?,?,?,?,?)",
				id, organizationId, id, role, NOW, NOW);
		return jwt.getAuthentication(jwt.createToken(id + "@example.test", "USER"));
	}

	private BreakGlassGrantResponse issue(String environment, int minutes) {
		return breakGlassService.issue(operator,
				new BreakGlassIssueRequest(PROJECT, environment, "provider 장애 대응", minutes), NOW);
	}

	// 원장 이벤트 한 건. metadata는 실제 수집 경로처럼 JSON 문자열로 넣는다.
	private UsageLog event(String environment, String metadataJson, LocalDateTime occurredAt,
			String accountingStatus, String callStatus, long tokens) {
		sequence++;
		return usageLogRepository.saveAndFlush(UsageLog.builder()
				.organizationId(ORG).projectId(PROJECT).environment(environment)
				.requestId("req-" + sequence).eventId("evt-" + sequence)
				.provider("openai").model("m").promptTokens(tokens).completionTokens(0L)
				.pricingVersion("v1").metadataJson(metadataJson)
				.accountingStatus(accountingStatus).callStatus(callStatus)
				.occurredAt(occurredAt).build());
	}

	private UsageLog tagged(String grantId, LocalDateTime occurredAt, String accountingStatus, String callStatus) {
		return event("prod", "{\"break_glass_grant_id\":\"" + grantId + "\",\"feature\":\"chat\"}", occurredAt,
				accountingStatus, callStatus, 100);
	}

	private void actual(UsageLog event, int revision, String amount) {
		AccountingAmount value = amount == null
				? new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.UNPRICED, null,
						"USD", AccountingAmount.Source.NONE)
				: new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED,
						new BigDecimal(amount), "USD", AccountingAmount.Source.PROVIDER_BILLING);
		valueRepository.saveAndFlush(UsageAccountingValue.of(event.getId(), revision, value, null,
				amount == null ? null : "invoice-" + event.getId() + "-" + revision, "TEST", "SYSTEM", "test"));
	}

	@Test
	void sumsBypassedCallsWithinWindowByLedgerRules() {
		String grantId = issue("prod", 60).grantId();

		// 가격 확정: 최신 개정본(0.200000)만 더한다.
		UsageLog priced = tagged(grantId, NOW.plusMinutes(5), "VERIFIED", "SUCCEEDED");
		actual(priced, 1, "0.100000");
		actual(priced, 2, "0.200000");
		// 가격 미상 개정본, ACTUAL 값이 아직 없는 이벤트 → 둘 다 unpriced.
		actual(tagged(grantId, NOW.plusMinutes(10), "PENDING", "SUCCEEDED"), 1, null);
		tagged(grantId, NOW.plusMinutes(15), "PENDING", "USAGE_UNKNOWN");
		// provider 실패: 과금 0으로 확정된 값.
		actual(tagged(grantId, NOW.plusMinutes(20), "VERIFIED", "PROVIDER_FAILED"), 1, "0.000000");
		// 기존 수집 경로: usage_events 금액을 따로 보여 준다.
		UsageLog legacy = tagged(grantId, NOW.plusMinutes(25), "LEGACY_UNVERIFIED", "LEGACY_UNKNOWN");
		legacy.setTotalCostUsd(new BigDecimal("0.500000"));
		usageLogRepository.saveAndFlush(legacy);
		// 격리: 세기만 하고 비용에서 뺀다.
		actual(tagged(grantId, NOW.plusMinutes(30), "QUARANTINED", "SUCCEEDED"), 1, "9.000000");

		BreakGlassUsageResponse usage = usageService.getUsage(operator, grantId, NOW.plusMinutes(40));
		BreakGlassUsageResponse.Summary within = usage.withinWindow();

		assertThat(usage.status()).isEqualTo(BreakGlassStatus.ACTIVE);
		assertThat(usage.windowEnd().toLocalDateTime()).isEqualTo(NOW.plusMinutes(60));
		assertThat(within.events()).isEqualTo(6);
		assertThat(within.totalTokens()).isEqualTo(600);
		assertThat(within.actualCostUsd()).isEqualByComparingTo("0.2");
		assertThat(within.unpricedEvents()).isEqualTo(2);
		assertThat(within.legacyEvents()).isEqualTo(1);
		assertThat(within.legacyCostUsd()).isEqualByComparingTo("0.5");
		assertThat(within.quarantinedEvents()).isEqualTo(1);
		assertThat(within.callStatusCounts()).isEqualTo(Map.of(
				"SUCCEEDED", 3L, "USAGE_UNKNOWN", 1L, "PROVIDER_FAILED", 1L, "LEGACY_UNKNOWN", 1L));
		assertThat(usage.outsideWindow().events()).isZero();
	}

	@Test
	void callsAfterExpiryAreReportedSeparately() {
		String grantId = issue("prod", 60).grantId();
		actual(tagged(grantId, NOW.plusMinutes(59), "VERIFIED", "SUCCEEDED"), 1, "0.100000");
		// 만료 시각과 같은 순간부터 기간 밖이다. SDK가 만료를 늦게 반영한 경우.
		actual(tagged(grantId, NOW.plusMinutes(60), "VERIFIED", "SUCCEEDED"), 1, "0.300000");

		BreakGlassUsageResponse usage = usageService.getUsage(operator, grantId, NOW.plusHours(2));

		assertThat(usage.status()).isEqualTo(BreakGlassStatus.EXPIRED);
		assertThat(usage.withinWindow().events()).isEqualTo(1);
		assertThat(usage.withinWindow().actualCostUsd()).isEqualByComparingTo("0.1");
		assertThat(usage.outsideWindow().events()).isEqualTo(1);
		assertThat(usage.outsideWindow().actualCostUsd()).isEqualByComparingTo("0.3");
	}

	@Test
	void revokedGrantWindowEndsAtRevocation() {
		String grantId = issue("prod", 60).grantId();
		breakGlassService.revoke(operator, grantId, new BreakGlassRevokeRequest("provider 복구"), NOW.plusMinutes(10));
		tagged(grantId, NOW.plusMinutes(5), "PENDING", "SUCCEEDED");
		tagged(grantId, NOW.plusMinutes(20), "PENDING", "SUCCEEDED");

		BreakGlassUsageResponse usage = usageService.getUsage(operator, grantId, NOW.plusMinutes(30));

		assertThat(usage.status()).isEqualTo(BreakGlassStatus.REVOKED);
		assertThat(usage.windowEnd().toLocalDateTime()).isEqualTo(NOW.plusMinutes(10));
		assertThat(usage.withinWindow().events()).isEqualTo(1);
		assertThat(usage.outsideWindow().events()).isEqualTo(1);
	}

	@Test
	void onlyExactKeyInSameScopeIsCounted() {
		String grantId = issue("prod", 60).grantId();
		String otherGrantId = issue("staging", 60).grantId();
		LocalDateTime at = NOW.plusMinutes(5);

		tagged(grantId, at, "PENDING", "SUCCEEDED");
		// 다른 grant, 다른 환경, 규칙에 맞지 않는 키, grant id가 다른 키의 값인 경우, 키 없는 이벤트는 모두 제외한다.
		tagged(otherGrantId, at, "PENDING", "SUCCEEDED");
		event("staging", "{\"break_glass_grant_id\":\"" + grantId + "\"}", at, "PENDING", "SUCCEEDED", 100);
		event("prod", "{\"breakGlassGrantId\":\"" + grantId + "\"}", at, "PENDING", "SUCCEEDED", 100);
		event("prod", "{\"note\":\"" + grantId + "\"}", at, "PENDING", "SUCCEEDED", 100);
		event("prod", null, at, "PENDING", "SUCCEEDED", 100);

		BreakGlassUsageResponse usage = usageService.getUsage(operator, grantId, NOW.plusMinutes(30));

		assertThat(usage.withinWindow().events()).isEqualTo(1);
		assertThat(usage.outsideWindow().events()).isZero();
	}

	@Test
	void grantWithoutBypassedCallsReturnsZeroSummaries() {
		String grantId = issue("prod", 60).grantId();

		BreakGlassUsageResponse usage = usageService.getUsage(viewer, grantId, NOW.plusMinutes(1));

		// 조회는 감사 이력처럼 역할과 관계없이 조직 멤버면 된다.
		assertThat(usage.withinWindow().events()).isZero();
		assertThat(usage.withinWindow().actualCostUsd()).isEqualByComparingTo("0");
		assertThat(usage.withinWindow().callStatusCounts()).isEmpty();
	}

	@Test
	void otherOrganizationAndUnknownGrantAreNotFound() {
		String grantId = issue("prod", 60).grantId();

		for (Runnable call : new Runnable[] {
				() -> usageService.getUsage(outsider, grantId, NOW),
				() -> usageService.getUsage(operator, "no-such-grant", NOW)}) {
			assertThatThrownBy(call::run)
					.isInstanceOf(ApiException.class)
					.extracting(e -> ((ApiException) e).getErrorCode())
					.isEqualTo(ErrorCode.NOT_FOUND);
		}
	}
}
