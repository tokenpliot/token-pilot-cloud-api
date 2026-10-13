package com.tokenledgercloud.api.domain.budget.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.budget.dto.BudgetUpsertRequest;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

/**
 * [이슈 #11] 예산 요약이 새 원장의 확정 지출을 더하고, 미정산 건수를 0원으로 숨기지 않는지 확인한다.
 * H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다.
 */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:budget-ledger;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class BudgetServiceLedgerTest {

    // BudgetService가 쓰는 기본 조직 id와 테스트 대상 프로젝트/환경/월.
    private static final String ORG = "default-org";
    private static final String PROJECT = "proj-b";
    private static final String ENV = "prod";
    private static final String MONTH = "2026-10";
    // 모든 이벤트는 2026-10-10 12:00에 발생한 것으로 만든다.
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);

    @Autowired BudgetService budgetService;
    @Autowired UsageLogRepository usageLogRepository;
    @Autowired UsageAccountingValueRepository valueRepository;

    @BeforeEach
    void createBudget() {
        // 한도 100달러의 월 예산을 만든다. (요약 조회에는 예산 설정이 필요하다)
        budgetService.upsert(new BudgetUpsertRequest(PROJECT, ENV, "MONTHLY", new BigDecimal("100"), null));
    }

    // 사용 이벤트를 저장하는 도우미. cost는 기존 방식의 금액 컬럼에 들어간다.
    private UsageLog event(String project, String eventId, String accountingStatus, String settlementState,
                           String cost) {
        return usageLogRepository.saveAndFlush(UsageLog.builder()
                .organizationId(ORG)
                .projectId(project)
                .environment(ENV)
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
    void spentAddsLegacyAndConfirmedLedgerAndReportsUnsettled() {
        // 기존 행: 10달러.
        event(PROJECT, "legacy", "LEGACY_UNVERIFIED", "UNSETTLED", "10.000000");
        // 새 원장, 정산 완료: 확정 금액 5달러 -> 지출에 더해진다.
        actual(event(PROJECT, "settled", "VERIFIED", "SETTLED", "0"), priced("5.000000"));
        // 새 원장, 가격 미상: 지출에 못 넣고 미정산 건수로 알려야 한다.
        actual(event(PROJECT, "unpriced", "PENDING", "UNSETTLED", "0"), unpriced());
        // 새 원장, 격리: 7달러가 있어도 지출에 넣지 않고 미정산 건수로 알려야 한다.
        actual(event(PROJECT, "quarantined", "QUARANTINED", "UNSETTLED", "0"), priced("7.000000"));

        var summary = budgetService.getSummary(PROJECT, ENV, MONTH);

        // 지출은 기존 10 + 확정 5 = 15달러여야 한다. (가격 미상은 0으로 더해져도 합계에는 변화가 없어야 한다)
        assertThat(summary.spentUsd()).isEqualByComparingTo("15");
        assertThat(summary.usagePercent()).isEqualByComparingTo("15.00");
        // 지출에 못 넣은 이벤트가 2건(가격 미상, 격리)이라는 사실이 보여야 한다.
        assertThat(summary.unsettledEventCount()).isEqualTo(2);
    }

    @Test
    void summaryWithoutLedgerEventsKeepsLegacyBehavior() {
        // 기존 행만 있을 때는 예전과 똑같이 기존 합계만 나와야 한다.
        event(PROJECT, "legacy", "LEGACY_UNVERIFIED", "UNSETTLED", "10.000000");

        var summary = budgetService.getSummary(PROJECT, ENV, MONTH);

        assertThat(summary.spentUsd()).isEqualByComparingTo("10");
        assertThat(summary.unsettledEventCount()).isZero();
    }

    @Test
    void ledgerEventsOfOtherProjectsAreNotCounted() {
        // 다른 프로젝트의 확정 지출과 미정산 이벤트는 이 예산에 들어오면 안 된다.
        actual(event("other-project", "other-settled", "VERIFIED", "SETTLED", "0"), priced("50.000000"));
        actual(event("other-project", "other-unpriced", "PENDING", "UNSETTLED", "0"), unpriced());

        var summary = budgetService.getSummary(PROJECT, ENV, MONTH);

        assertThat(summary.spentUsd()).isEqualByComparingTo("0");
        assertThat(summary.unsettledEventCount()).isZero();
    }
}
