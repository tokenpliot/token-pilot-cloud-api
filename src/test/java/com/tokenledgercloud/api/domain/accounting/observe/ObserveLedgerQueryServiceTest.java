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
import com.tokenledgercloud.api.domain.accounting.observe.dto.UnpricedSummaryResponse;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;

/** 조회 3종의 집계 규칙과 조직 범위를 확인한다. H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다. */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:observe-ledger-query;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class ObserveLedgerQueryServiceTest {

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

    // 사용 이벤트를 저장하는 도우미.
    private UsageLog event(String project, String eventId, String accountingStatus, String settlementState,
                           String provider, String model, long tokens) {
        return usageLogRepository.saveAndFlush(UsageLog.builder()
                .organizationId(ORG)
                .projectId(project)
                .environment("prod")
                .requestId("req-" + eventId)
                .eventId(eventId)
                .provider(provider)
                .model(model)
                .totalTokens(tokens)
                .promptCostUsd(BigDecimal.ZERO)
                .pricingVersion("ledger")
                .sourceType("observe")
                .accountingStatus(accountingStatus)
                .callStatus("SUCCEEDED")
                .settlementState(settlementState)
                .occurredAt(OCCURRED_AT)
                .build());
    }

    // 이벤트에 실제(ACTUAL) 원장 값을 한 개정본 추가하는 도우미.
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
    void ingestionLagCountsOnlyLedgerEventsOfThisProject() {
        // 원장 이벤트 2개(미정산 1, 정산 1), 기존 행 1개, 다른 프로젝트 이벤트 1개를 저장한다.
        event(projectId, "e1", "PENDING", "UNSETTLED", "openai", "gpt-a", 10);
        event(projectId, "e2", "VERIFIED", "SETTLED", "openai", "gpt-a", 10);
        event(projectId, "e3", "LEGACY_UNVERIFIED", "UNSETTLED", "openai", "gpt-a", 10);
        event("other-project", "e4", "PENDING", "UNSETTLED", "openai", "gpt-a", 10);

        var lag = service.getIngestionLag(ORG, projectId, null, PERIOD, LocalDateTime.now().plusMinutes(5));

        // 기존 행과 다른 프로젝트 이벤트는 세지 않는다.
        assertThat(lag.eventCount()).isEqualTo(2);
        assertThat(lag.unsettledCount()).isEqualTo(1);
        // 마지막 수신 시각이 있고, 지난 시간은 0 이상이다.
        assertThat(lag.lastReceivedAt()).isNotNull();
        assertThat(lag.secondsSinceLastReceived()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void ingestionLagIsEmptyWhenNothingWasReceived() {
        // 이벤트가 하나도 없는 프로젝트를 조회한다.
        var lag = service.getIngestionLag(ORG, projectId, null, PERIOD, LocalDateTime.now());
        // 건수는 0이고, 시각과 지난 시간은 null이다. (0초로 위장하지 않는다)
        assertThat(lag.eventCount()).isZero();
        assertThat(lag.lastReceivedAt()).isNull();
        assertThat(lag.secondsSinceLastReceived()).isNull();
    }

    @Test
    void unattributedCostUsesLatestRevisionOfQuarantinedEventsOnly() {
        // 격리 이벤트 q1: 개정 1은 3달러, 정정된 개정 2는 4달러.
        UsageLog q1 = event(projectId, "q1", "QUARANTINED", "UNSETTLED", "openai", "gpt-a", 10);
        actual(q1, 1, priced("3.000000"));
        actual(q1, 2, priced("4.000000"));
        // 격리 이벤트 q3: 가격 미상이라 금액에 더하지 않는다.
        UsageLog q3 = event(projectId, "q3", "QUARANTINED", "UNSETTLED", "openai", "gpt-a", 10);
        actual(q3, 1, unpriced());
        // 격리가 아닌 이벤트의 금액(9달러)은 제외되어야 한다.
        UsageLog ok = event(projectId, "ok", "PENDING", "UNSETTLED", "openai", "gpt-a", 10);
        actual(ok, 1, priced("9.000000"));

        var response = service.getUnattributedCost(ORG, projectId, null, PERIOD);

        // 격리 이벤트는 2건이고, 금액은 최신 개정본 4달러만 더해진다. (3 + 4 = 7이 되면 이중 계산이다)
        assertThat(response.quarantinedEventCount()).isEqualTo(2);
        assertThat(response.pricedActualAmountUsd()).isEqualByComparingTo("4.000000");
    }

    @Test
    void unattributedCostIsZeroWhenNothingIsQuarantined() {
        // 격리 이벤트가 없으면 금액은 null이 아니라 0이어야 한다.
        var response = service.getUnattributedCost(ORG, projectId, null, PERIOD);
        assertThat(response.quarantinedEventCount()).isZero();
        assertThat(response.pricedActualAmountUsd()).isEqualByComparingTo("0");
    }

    @Test
    void unpricedListsOnlyEventsWhoseLatestActualIsUnpriced() {
        // u1: 가격 미상 그대로 -> 포함.
        UsageLog u1 = event(projectId, "u1", "PENDING", "UNSETTLED", "openai", "gpt-a", 100);
        actual(u1, 1, unpriced());
        // u2: 처음엔 가격 미상이었지만 개정 2에서 가격이 확정됨 -> 제외.
        UsageLog u2 = event(projectId, "u2", "PENDING", "UNSETTLED", "openai", "gpt-a", 50);
        actual(u2, 1, unpriced());
        actual(u2, 2, priced("1.000000"));
        // u3: 다른 공급자의 가격 미상 -> 포함.
        UsageLog u3 = event(projectId, "u3", "PENDING", "UNSETTLED", "anthropic", "claude-x", 200);
        actual(u3, 1, unpriced());

        var response = service.getUnpriced(ORG, projectId, null, PERIOD);

        // 가격 누락은 2건(u1, u3)이다.
        assertThat(response.unpricedEventCount()).isEqualTo(2);
        assertThat(response.items()).extracting(UnpricedSummaryResponse.Item::model)
                .containsExactlyInAnyOrder("gpt-a", "claude-x");
        // gpt-a 항목은 u1 한 건(100토큰)만 센다.
        var gpt = response.items().stream().filter(item -> item.model().equals("gpt-a")).findFirst().orElseThrow();
        assertThat(gpt.eventCount()).isEqualTo(1);
        assertThat(gpt.totalTokens()).isEqualTo(100);
    }

    @Test
    void otherOrganizationsProjectIsNotFound() {
        // 다른 조직 소유의 프로젝트를 만든다.
        String foreignProject = newProject("org-2");
        // 우리 조직(org-1)으로 조회하면 존재 여부와 상관없이 NOT_FOUND여야 한다.
        assertThatThrownBy(() -> service.getUnpriced(ORG, foreignProject, null, PERIOD))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getUnattributedCost(ORG, foreignProject, null, PERIOD))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.getIngestionLag(ORG, foreignProject, null, PERIOD, LocalDateTime.now()))
                .isInstanceOf(ApiException.class);
    }
}
