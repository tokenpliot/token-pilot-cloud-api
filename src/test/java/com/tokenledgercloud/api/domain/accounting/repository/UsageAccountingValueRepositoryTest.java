package com.tokenledgercloud.api.domain.accounting.repository;

import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Kind.ACTUAL;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.PRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.PricingStatus.UNPRICED;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.NONE;
import static com.tokenledgercloud.api.domain.accounting.AccountingAmount.Source.SERVER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;

import jakarta.persistence.EntityManager;

/** H2 + Hibernate 스키마 생성으로 원장 엔티티의 저장/조회/UNIQUE 제약을 확인한다. (Flyway는 끈다) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accounting-ledger;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false"
})
// 각 테스트가 끝나면 롤백해서 서로 영향을 주지 않게 한다.
@Transactional
class UsageAccountingValueRepositoryTest {

    @Autowired UsageAccountingValueRepository repository;
    @Autowired EntityManager entityManager;

    // 가격 스냅샷으로 계산된 실제 금액을 만드는 도우미.
    private static UsageAccountingValue priced(String eventId, int revision, String amount) {
        var money = new AccountingAmount(ACTUAL, PRICED, new BigDecimal(amount), "USD", SERVER_CATALOG);
        return UsageAccountingValue.of(eventId, revision, money, "snap-1", null, "PRICED_BY_CATALOG", "SYSTEM", "ingest");
    }

    @Test
    void unpricedActualIsStoredAsNullNotZero() {
        // 가격을 모르는 실제 값을 저장한다.
        var money = new AccountingAmount(ACTUAL, UNPRICED, null, "USD", NONE);
        var saved = repository.saveAndFlush(
                UsageAccountingValue.of("event-1", 1, money, null, null, "PRICE_MISSING", "SYSTEM", "ingest"));
        // 영속성 컨텍스트를 비워서 DB에서 다시 읽게 한다.
        entityManager.clear();
        // 다시 읽은 금액이 0이 아니라 null이어야 한다.
        assertThat(repository.findById(saved.getId())).get()
                .satisfies(row -> assertThat(row.getAmount()).isNull());
    }

    @Test
    void latestRevisionIsReturned() {
        // 같은 이벤트에 개정 1, 2를 저장한다.
        repository.saveAndFlush(priced("event-1", 1, "0.001000"));
        repository.saveAndFlush(priced("event-1", 2, "0.002000"));
        // 최신 개정본은 2번이어야 한다.
        var latest = repository.findFirstByUsageEventIdAndValueKindOrderByRevisionDesc("event-1", ACTUAL);
        assertThat(latest).isPresent();
        assertThat(latest.get().getRevision()).isEqualTo(2);
        assertThat(latest.get().getAmount()).isEqualByComparingTo("0.002000");
    }

    @Test
    void sameEventKindRevisionCannotBeInsertedTwice() {
        // 같은 (이벤트, 종류, 개정)을 처음 저장한다.
        repository.saveAndFlush(priced("event-1", 1, "0.001000"));
        // 같은 조합을 한 번 더 저장하면 UNIQUE 제약으로 거부되어야 한다. (중복 이벤트가 비용을 두 번 올리지 못하는 근거)
        assertThatThrownBy(() -> repository.saveAndFlush(priced("event-1", 1, "0.001000")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
