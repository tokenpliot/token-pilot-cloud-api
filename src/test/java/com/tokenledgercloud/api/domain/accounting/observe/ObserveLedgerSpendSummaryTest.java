package com.tokenledgercloud.api.domain.accounting.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;

/**
 * 월 지출 요약이 기존 합계와 새 원장의 확정 금액을 따로 보여주고,
 * 지출에서 빠지는 이벤트를 빠짐없이 "반영 안 됨"으로 세는지 확인한다.
 */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ledger-spend;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class ObserveLedgerSpendSummaryTest {

	// 테스트 조직과 월. 모든 이벤트는 2026-10-10 12:00에 발생한 것으로 만든다.
	private static final String ORG = "org-1";
	private static final String MONTH = "2026-10";
	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);

	@Autowired ObserveLedgerQueryService service;
	@Autowired UsageLogRepository usageLogRepository;
	@Autowired UsageAccountingValueRepository valueRepository;
	@Autowired ProjectRepository projectRepository;

	// 각 테스트가 쓰는 프로젝트 id.
	private String projectId;

	@BeforeEach
	void seedProject() {
		// 테스트마다 새 프로젝트를 만든다. projectKey는 UNIQUE라 매번 다르게 만든다.
		projectId = newProject(ORG);
	}

	// 지정한 조직 소유의 프로젝트를 만들고 id를 돌려주는 도우미.
	private String newProject(String organizationId) {
		return projectRepository.saveAndFlush(Project.builder()
				.organizationId(organizationId)
				.projectKey("key-" + UUID.randomUUID().toString().substring(0, 8))
				.name("test-project")
				.status(ProjectStatus.ACTIVE)
				.build()).getId();
	}

	// 사용 이벤트를 저장하는 도우미. cost는 기존 방식의 금액 컬럼에 들어간다.
	private UsageLog event(String project, String eventId, String accountingStatus, String settlementState,
			String cost) {
		return usageLogRepository.saveAndFlush(UsageLog.builder()
				.organizationId(ORG)
				.projectId(project)
				.environment("prod")
				.requestId("req-" + eventId)
				.eventId(eventId)
				.provider("openai")
				.model("gpt-test")
				.totalTokens(10L)
				.promptCostUsd(new BigDecimal(cost))
				.pricingVersion("test")
				.sourceType("observe")
				.accountingStatus(accountingStatus)
				.callStatus("SUCCEEDED")
				.settlementState(settlementState)
				.occurredAt(OCCURRED_AT)
				.build());
	}

	// 이벤트에 실제(ACTUAL) 원장 값을 개정 1로 추가하는 도우미.
	private void actual(UsageLog event, AccountingAmount amount) {
		String snapshotId = amount.source() == AccountingAmount.Source.SERVER_CATALOG ? "snap-1" : null;
		valueRepository.saveAndFlush(UsageAccountingValue.of(
				event.getId(), 1, amount, snapshotId, null, "TEST", "SYSTEM", "test"));
	}

	// 가격이 확정된 실제 금액.
	private static AccountingAmount priced(String amount) {
		return new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED,
				new BigDecimal(amount), "USD", AccountingAmount.Source.SERVER_CATALOG);
	}

	// 가격을 모르는 실제 금액. 금액은 null이다.
	private static AccountingAmount unpriced() {
		return new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.UNPRICED,
				null, "USD", AccountingAmount.Source.NONE);
	}

	@Test
	void totalAddsLegacyAndConfirmedLedgerAndCountsEveryExcludedEvent() {
		// 기존 행: 10달러.
		event(projectId, "legacy", "LEGACY_UNVERIFIED", "UNSETTLED", "10.000000");
		// 새 원장, 정산 완료: 확정 금액 5달러 -> 지출에 더해진다.
		actual(event(projectId, "settled", "VERIFIED", "SETTLED", "0"), priced("5.000000"));
		// 새 원장, 가격 미상 -> 지출에서 빠지고 "반영 안 됨"으로 센다.
		actual(event(projectId, "unpriced", "PENDING", "UNSETTLED", "0"), unpriced());
		// 격리 + 미정산 -> 지출에서 빠지고 "반영 안 됨"으로 센다.
		actual(event(projectId, "quarantined-open", "QUARANTINED", "UNSETTLED", "0"), priced("7.000000"));
		// 격리 + 정산 완료: writer가 실제로 만들 수 있는 상태다. 지출에서 빠지므로 반드시 "반영 안 됨"으로 세야 한다.
		actual(event(projectId, "quarantined-settled", "QUARANTINED", "SETTLED", "0"), priced("2.000000"));

		var summary = service.getSpendSummary(ORG, projectId, null, MONTH);

		// 지출은 기존 10 + 확정 5 = 15달러여야 한다. 격리 금액(7, 2)은 더해지지 않는다.
		assertThat(summary.legacySpentUsd()).isEqualByComparingTo("10");
		assertThat(summary.ledgerConfirmedSpentUsd()).isEqualByComparingTo("5");
		assertThat(summary.totalSpentUsd()).isEqualByComparingTo("15");
		// 지출에서 빠진 이벤트 3건(가격 미상, 격리 둘)이 모두 세어져야 한다.
		assertThat(summary.unreflectedEventCount()).isEqualTo(3);
		assertThat(summary.month()).isEqualTo("2026-10");
	}

	@Test
	void summaryWithoutLedgerEventsEqualsLegacyOnly() {
		// 기존 행만 있을 때는 기존 합계만 나오고, 반영 안 된 이벤트는 0이다.
		event(projectId, "legacy", "LEGACY_UNVERIFIED", "UNSETTLED", "10.000000");

		var summary = service.getSpendSummary(ORG, projectId, null, MONTH);

		assertThat(summary.totalSpentUsd()).isEqualByComparingTo("10");
		assertThat(summary.unreflectedEventCount()).isZero();
	}

	@Test
	void eventsOfOtherProjectsAreNotCounted() {
		// 다른 프로젝트의 확정 지출과 미정산 이벤트는 들어오면 안 된다.
		actual(event("other-project", "other-settled", "VERIFIED", "SETTLED", "0"), priced("50.000000"));
		actual(event("other-project", "other-unpriced", "PENDING", "UNSETTLED", "0"), unpriced());

		var summary = service.getSpendSummary(ORG, projectId, null, MONTH);

		assertThat(summary.totalSpentUsd()).isEqualByComparingTo("0");
		assertThat(summary.unreflectedEventCount()).isZero();
	}

	@Test
	void invalidMonthAndForeignProjectAreRejected() {
		// 형식이 틀린 월은 거절한다.
		assertThatThrownBy(() -> service.getSpendSummary(ORG, projectId, null, "2026/10"))
				.isInstanceOf(ApiException.class);
		// 다른 조직의 프로젝트는 존재 여부와 상관없이 거절한다.
		String foreignProject = newProject("org-2");
		assertThatThrownBy(() -> service.getSpendSummary(ORG, foreignProject, null, MONTH))
				.isInstanceOf(ApiException.class);
	}
}
