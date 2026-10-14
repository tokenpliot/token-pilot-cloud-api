package com.tokenledgercloud.api.domain.accounting.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * usage_accounting_values 테이블의 한 행. 이벤트 하나에 대한 금액(추정/실제)의 한 개정본(revision)이다.
 * 한 번 기록하면 수정하지 않는 append-only 원장이므로 setter를 두지 않는다.
 */
// JPA 엔티티임을 선언한다.
@Entity
// 매핑할 테이블 이름과, (이벤트, 종류, 개정) 조합의 UNIQUE 제약을 선언한다. (V6 SQL의 uk_accounting_event_kind_revision과 같다)
@Table(name = "usage_accounting_values", uniqueConstraints = {
        @UniqueConstraint(name = "uk_accounting_event_kind_revision",
                columnNames = {"usage_event_id", "value_kind", "revision"})
})
// Hibernate가 이 엔티티의 UPDATE를 보내지 않도록 한다. (원장은 수정 금지)
@Immutable
// 모든 필드의 getter를 만든다.
@Getter
// JPA가 쓰는 기본 생성자. 외부에서는 쓰지 못하게 protected로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageAccountingValue {

    // 기본키. UUID 문자열(36자).
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    // 어느 usage_events 행에 대한 값인지. 연관관계 대신 id만 들고 있는다.
    @Column(name = "usage_event_id", nullable = false, length = 36, updatable = false)
    private String usageEventId;

    // ESTIMATED(추정) 또는 ACTUAL(실제).
    @Enumerated(EnumType.STRING)
    @Column(name = "value_kind", nullable = false, length = 20, updatable = false)
    private AccountingAmount.Kind valueKind;

    // 같은 (이벤트, 종류) 안에서의 개정 번호. 1부터 시작한다.
    @Column(nullable = false, updatable = false)
    private int revision;

    // PRICED(가격 확정) 또는 UNPRICED(가격 모름).
    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_status", nullable = false, length = 20, updatable = false)
    private AccountingAmount.PricingStatus pricingStatus;

    // 금액(USD). UNPRICED이면 반드시 null이다. 0이 아니라 null인 것이 핵심이다.
    @Column(precision = 18, scale = 6, updatable = false)
    private BigDecimal amount;

    // 통화. 현재는 USD만 허용한다.
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    // 금액의 출처: SERVER_CATALOG / PROVIDER_BILLING / NONE.
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_source", nullable = false, length = 30, updatable = false)
    private AccountingAmount.Source costSource;

    // 가격 계산에 쓴 pricing_snapshots 행의 id. SERVER_CATALOG일 때 필수.
    @Column(name = "pricing_snapshot_id", length = 36, updatable = false)
    private String pricingSnapshotId;

    // 공급자 청구서 등 근거 식별자. PROVIDER_BILLING일 때 필수.
    @Column(name = "evidence_reference", length = 255, updatable = false)
    private String evidenceReference;

    // 이 값을 기록한 사유 코드.
    @Column(name = "reason_code", nullable = false, length = 50, updatable = false)
    private String reasonCode;

    // 기록한 주체의 종류(SYSTEM, USER 등).
    @Column(name = "actor_type", nullable = false, length = 30, updatable = false)
    private String actorType;

    // 기록한 주체의 식별자.
    @Column(name = "actor_id", nullable = false, length = 128, updatable = false)
    private String actorId;

    // 기록 시각.
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    /**
     * 새 원장 행을 만든다. V6의 ck_accounting_value CHECK 제약과 같은 규칙을 DB에 보내기 전에 먼저 검사한다.
     */
    public static UsageAccountingValue of(String usageEventId, int revision, AccountingAmount amount,
                                          String pricingSnapshotId, String evidenceReference,
                                          String reasonCode, String actorType, String actorId) {
        // 이벤트 id는 비어 있으면 안 된다.
        requireText(usageEventId, "usageEventId");
        // 개정 번호는 1 이상이어야 한다. (ck_accounting_revision)
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        // 금액 객체는 필수다.
        Objects.requireNonNull(amount, "amount");
        // 사유 코드와 기록 주체는 비어 있으면 안 된다.
        requireText(reasonCode, "reasonCode");
        requireText(actorType, "actorType");
        requireText(actorId, "actorId");

        // 가격을 모르는 값은 가격 스냅샷을 참조할 수 없다. (UNPRICED 분기 규칙)
        if (amount.pricingStatus() == AccountingAmount.PricingStatus.UNPRICED) {
            if (pricingSnapshotId != null) {
                throw new IllegalArgumentException("UNPRICED value must not reference a pricing snapshot");
            }
        // 서버 가격표로 계산한 값은 어떤 가격 스냅샷을 썼는지 반드시 남긴다.
        } else if (amount.source() == AccountingAmount.Source.SERVER_CATALOG) {
            requireText(pricingSnapshotId, "pricingSnapshotId");
        // 공급자 청구 값은 근거 식별자를 반드시 남긴다.
        } else if (amount.source() == AccountingAmount.Source.PROVIDER_BILLING) {
            requireText(evidenceReference, "evidenceReference");
        }

        // 검증을 통과했으니 엔티티를 만들고 값을 채운다.
        UsageAccountingValue value = new UsageAccountingValue();
        value.id = UUID.randomUUID().toString();
        value.usageEventId = usageEventId;
        value.valueKind = amount.kind();
        value.revision = revision;
        value.pricingStatus = amount.pricingStatus();
        value.amount = amount.amount();
        value.currency = amount.currency();
        value.costSource = amount.source();
        value.pricingSnapshotId = pricingSnapshotId;
        value.evidenceReference = evidenceReference;
        value.reasonCode = reasonCode;
        value.actorType = actorType;
        value.actorId = actorId;
        value.recordedAt = LocalDateTime.now();
        return value;
    }

    // 문자열이 null이거나 공백뿐이면 예외를 던지는 도우미 메서드.
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
