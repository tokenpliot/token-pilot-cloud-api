package com.tokenledgercloud.api.domain.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;

/**
 * 원장 writer의 멱등성과 상태 규칙을 확인한다. H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다.
 * writer가 REQUIRES_NEW로 커밋하므로 테스트에 @Transactional을 붙이지 않고, 테스트마다 서로 다른 eventId를 쓴다.
 */
// 이 테스트 전용 인메모리 DB를 만들고, Hibernate가 엔티티대로 테이블을 생성하게 한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounting-ledger-writer;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
class AccountingLedgerWriterTest {

    // 본문 지문 예시 값 두 개. 길이가 64자여야 한다.
    private static final String FINGERPRINT_A = "a".repeat(64);
    private static final String FINGERPRINT_B = "b".repeat(64);

    @Autowired AccountingLedgerWriter writer;
    @Autowired UsageLogRepository usageLogRepository;
    @Autowired UsageAccountingValueRepository valueRepository;

    // 가격이 확정된 실제 금액.
    private static AccountingAmount pricedActual() {
        return new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED,
                new BigDecimal("0.005150"), "USD", AccountingAmount.Source.SERVER_CATALOG);
    }

    // 가격을 모르는 실제 금액. 금액은 null이다.
    private static AccountingAmount unpricedActual() {
        return new AccountingAmount(AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.UNPRICED,
                null, "USD", AccountingAmount.Source.NONE);
    }

    // 테스트용 명령을 만드는 도우미. eventId, 지문, 호출 결과, 금액만 바꿔서 쓴다.
    private static AccountingLedgerCommand command(String eventId, String fingerprint,
                                                   CallStatus callStatus, AccountingAmount amount) {
        // 가격 확정 금액이면 스냅샷 id를 같이 넘긴다. (SERVER_CATALOG 규칙)
        String snapshotId = amount != null && amount.source() == AccountingAmount.Source.SERVER_CATALOG
                ? "snapshot-1" : null;
        return new AccountingLedgerCommand(
                "org-1", "project-1", "key-1", "prod", "req-" + eventId, eventId, fingerprint,
                "openai", "gpt-test", 100, 50, 0, 0, 150,
                LocalDateTime.of(2026, 10, 10, 12, 0),
                callStatus, amount, snapshotId, null, "OBSERVE_INGEST", "SYSTEM", "ingest");
    }

    // 이벤트 id에 연결된 원장 행 목록을 읽는 도우미.
    private List<UsageAccountingValue> valuesOf(String usageEventId) {
        return valueRepository.findAll().stream()
                .filter(value -> value.getUsageEventId().equals(usageEventId))
                .toList();
    }

    @Test
    void pricedActualSettlesAndStoresOneLedgerValue() {
        // 가격 확정된 실제 사용량을 기록한다.
        var result = writer.record(command("ev-priced", FINGERPRINT_A, CallStatus.SUCCEEDED, pricedActual()));
        assertThat(result.duplicate()).isFalse();
        // 이벤트는 PENDING / SUCCEEDED / SETTLED로 저장된다.
        UsageLog event = usageLogRepository.findById(result.usageEventId()).orElseThrow();
        assertThat(event.getAccountingStatus()).isEqualTo("PENDING");
        assertThat(event.getCallStatus()).isEqualTo("SUCCEEDED");
        assertThat(event.getSettlementState()).isEqualTo("SETTLED");
        // 원장에는 개정 1 한 줄이 있고 금액이 들어 있다.
        List<UsageAccountingValue> values = valuesOf(result.usageEventId());
        assertThat(values).hasSize(1);
        assertThat(values.get(0).getRevision()).isEqualTo(1);
        assertThat(values.get(0).getAmount()).isEqualByComparingTo("0.005150");
    }

    @Test
    void sameEventTwiceAddsNoSecondLedgerRow() {
        // 같은 이벤트를 두 번 보낸다.
        var first = writer.record(command("ev-dup", FINGERPRINT_A, CallStatus.SUCCEEDED, pricedActual()));
        var second = writer.record(command("ev-dup", FINGERPRINT_A, CallStatus.SUCCEEDED, pricedActual()));
        // 두 번째는 중복으로 판정되고 같은 이벤트 id를 돌려준다.
        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.usageEventId()).isEqualTo(first.usageEventId());
        // 원장 행은 여전히 한 줄이다. (비용이 두 번 오르지 않는다)
        assertThat(valuesOf(first.usageEventId())).hasSize(1);
    }

    @Test
    void sameEventWithDifferentPayloadIsRejected() {
        // 같은 eventId를 먼저 기록한다.
        writer.record(command("ev-conflict", FINGERPRINT_A, CallStatus.SUCCEEDED, pricedActual()));
        // 같은 eventId에 다른 본문 지문이 오면 거절되어야 한다.
        assertThatThrownBy(() -> writer.record(
                command("ev-conflict", FINGERPRINT_B, CallStatus.SUCCEEDED, pricedActual())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unpricedActualStaysUnsettledWithNullAmount() {
        // 가격을 모르는 실제 사용량을 기록한다.
        var result = writer.record(command("ev-unpriced", FINGERPRINT_A, CallStatus.SUCCEEDED, unpricedActual()));
        // 정산 상태는 UNSETTLED로 남는다.
        UsageLog event = usageLogRepository.findById(result.usageEventId()).orElseThrow();
        assertThat(event.getSettlementState()).isEqualTo("UNSETTLED");
        // 원장 금액은 0이 아니라 null이다.
        List<UsageAccountingValue> values = valuesOf(result.usageEventId());
        assertThat(values).hasSize(1);
        assertThat(values.get(0).getAmount()).isNull();
    }

    @Test
    void unknownUsageCreatesNoLedgerValue() {
        // 사용량을 알 수 없는 호출을 기록한다.
        var result = writer.record(command("ev-unknown", FINGERPRINT_A, CallStatus.USAGE_UNKNOWN, null));
        // 이벤트는 USAGE_UNKNOWN / UNSETTLED로 저장되고
        UsageLog event = usageLogRepository.findById(result.usageEventId()).orElseThrow();
        assertThat(event.getCallStatus()).isEqualTo("USAGE_UNKNOWN");
        assertThat(event.getSettlementState()).isEqualTo("UNSETTLED");
        // 원장 값은 만들어지지 않는다.
        assertThat(valuesOf(result.usageEventId())).isEmpty();
    }

    @Test
    void amountIsRequiredUnlessUsageIsUnknown() {
        // 사용량을 아는 호출인데 금액이 없으면 명령 생성 단계에서 거부되어야 한다.
        assertThatThrownBy(() -> command("ev-bad", FINGERPRINT_A, CallStatus.SUCCEEDED, null))
                .isInstanceOf(IllegalArgumentException.class);
        // 사용량을 모르는 호출인데 금액이 있어도 거부되어야 한다.
        assertThatThrownBy(() -> command("ev-bad2", FINGERPRINT_A, CallStatus.USAGE_UNKNOWN, pricedActual()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
