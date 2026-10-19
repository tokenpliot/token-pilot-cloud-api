package com.tokenledgercloud.api.domain.accounting.observe;

import static org.assertj.core.api.Assertions.assertThat;

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

/** RECONCILIATION_REQUIRED / UNPRICED 분류가 조회 결과에 실제로 나타나는지 확인한다. */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:observe-ledger-reconciliation;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class ObserveLedgerReconciliationTest {

	// 테스트 조직과 기간. 모든 이벤트는 2026-10-10 12:00에 발생한 것으로 만든다.
	private static final String ORG = "org-1";
	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);
	private static final LedgerPeriod PERIOD = new LedgerPeriod(
			LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0));

	@Autowired ObserveLedgerQueryService service;
	@Autowired UsageLogRepository usageLogRepository;
	@Autowired UsageAccountingValueRepository valueRepository;
	@Autowired ProjectRepository projectRepository;

	// 각 테스트가 쓰는 프로젝트 id.
	private String projectId;

	@BeforeEach
	void seedProject() {
		// 테스트마다 새 프로젝트를 만든다. projectKey는 UNIQUE라 매번 다르게 만든다.
		projectId = projectRepository.saveAndFlush(Project.builder()
				.organizationId(ORG)
				.projectKey("key-" + UUID.randomUUID().toString().substring(0, 8))
				.name("test-project")
				.status(ProjectStatus.ACTIVE)
				.build()).getId();
	}

	// 사용 이벤트를 저장하는 도우미.
	private UsageLog event(String eventId, String accountingStatus, String callStatus, String settlementState) {
		return usageLogRepository.saveAndFlush(UsageLog.builder()
				.organizationId(ORG)
				.projectId(projectId)
				.environment("prod")
				.requestId("req-" + eventId)
				.eventId(eventId)
				.provider("openai")
				.model("gpt-test")
				.totalTokens(10L)
				.promptCostUsd(BigDecimal.ZERO)
				.pricingVersion("ledger")
				.sourceType("observe")
				.accountingStatus(accountingStatus)
				.callStatus(callStatus)
				.settlementState(settlementState)
				.occurredAt(OCCURRED_AT)
				.build());
	}

	// 이벤트에 실제(ACTUAL) 원장 값을 지정한 개정 번호로 추가하는 도우미.
	private void actual(UsageLog event, int revision, AccountingAmount amount) {
		String snapshotId = amount.source() == AccountingAmount.Source.SERVER_CATALOG ? "snap-1" : null;
		valueRepository.saveAndFlush(UsageAccountingValue.of(
				event.getId(), revision, amount, snapshotId, null, "TEST", "SYSTEM", "test"));
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
	void classifiesUnsettledEventsIntoUnpricedAndReconciliationRequired() {
		// 가격 미상 이벤트 2건 -> UNPRICED.
		actual(event("p1", "PENDING", "SUCCEEDED", "UNSETTLED"), 1, unpriced());
		actual(event("p2", "PENDING", "SUCCEEDED", "UNSETTLED"), 1, unpriced());
		// 격리 이벤트 1건, 사용량 미상 이벤트 1건 -> RECONCILIATION_REQUIRED.
		event("q1", "QUARANTINED", "SUCCEEDED", "UNSETTLED");
		event("k1", "PENDING", "USAGE_UNKNOWN", "UNSETTLED");
		// 가격이 확정되어 정산된 이벤트, 아직 실제 값이 없는 대기 이벤트 -> 어느 쪽도 아니다.
		actual(event("s1", "VERIFIED", "SUCCEEDED", "SETTLED"), 1, priced("1.000000"));
		event("w1", "PENDING", "SUCCEEDED", "UNSETTLED");
		// 기존 행 -> 제외.
		event("l1", "LEGACY_UNVERIFIED", "LEGACY_UNKNOWN", "UNSETTLED");

		var response = service.getReconciliation(ORG, projectId, null, PERIOD);

		assertThat(response.unpricedEventCount()).isEqualTo(2);
		assertThat(response.reconciliationRequiredEventCount()).isEqualTo(2);
	}

	@Test
	void latestRevisionDecidesWhetherEventIsStillUnpriced() {
		// 개정 1은 가격 미상이었지만 개정 2에서 가격이 확정된 이벤트는 더 이상 UNPRICED가 아니다.
		UsageLog repriced = event("r1", "PENDING", "SUCCEEDED", "UNSETTLED");
		actual(repriced, 1, unpriced());
		actual(repriced, 2, priced("2.000000"));

		var response = service.getReconciliation(ORG, projectId, null, PERIOD);

		assertThat(response.unpricedEventCount()).isZero();
		assertThat(response.reconciliationRequiredEventCount()).isZero();
	}

	@Test
	void emptyProjectHasNothingToReconcile() {
		// 이벤트가 없으면 두 값 모두 0이다.
		var response = service.getReconciliation(ORG, projectId, null, PERIOD);
		assertThat(response.unpricedEventCount()).isZero();
		assertThat(response.reconciliationRequiredEventCount()).isZero();
	}

	@Test
	void quarantinedEventStillAppearsEvenWhenRecordedAsSettled() {
		// writer는 실제 사용량에 가격이 확정되면 SETTLED로 기록한다. 격리된 이벤트가 이 상태여도 보여야 한다.
		actual(event("qs", "QUARANTINED", "SUCCEEDED", "SETTLED"), 1, priced("3.000000"));

		var response = service.getReconciliation(ORG, projectId, null, PERIOD);

		// 지출에서도 빠지는 이벤트라서, 여기에도 안 나타나면 어디서도 볼 수 없다.
		assertThat(response.reconciliationRequiredEventCount()).isEqualTo(1);
		assertThat(response.unpricedEventCount()).isZero();
	}
}
