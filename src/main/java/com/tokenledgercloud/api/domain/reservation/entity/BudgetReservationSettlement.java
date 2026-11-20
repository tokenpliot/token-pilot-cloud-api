package com.tokenledgercloud.api.domain.reservation.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * budget_reservation_settlements 테이블의 한 행. 적용한 정산 요청 하나와 그 결과다.
 * 같은 Idempotency-Key·같은 본문의 재전송은 이 행의 결과를 그대로 돌려준다. 한 번 기록하면 수정하지 않는다.
 */
// JPA 엔티티임을 선언한다.
@Entity
// 테이블 이름, 멱등성 UNIQUE 제약, 예약별 조회 인덱스. (V8 SQL과 같다)
@Table(name = "budget_reservation_settlements",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_reservation_settlement_idempotency",
						columnNames = {"project_id", "idempotency_key"})
		},
		indexes = {
				@Index(name = "idx_reservation_settlement_reservation", columnList = "reservation_id, created_at")
		})
// Hibernate가 이 엔티티의 UPDATE를 보내지 않도록 한다. (기록 수정 금지)
@Immutable
// 모든 필드의 getter를 만든다.
@Getter
// JPA가 쓰는 기본 생성자. 외부에서는 쓰지 못하게 protected로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetReservationSettlement {

	// 기본키. UUID 문자열(36자).
	@Id
	@Column(length = 36, updatable = false)
	private String id;

	// 정산한 예약.
	@Column(name = "reservation_id", nullable = false, length = 36, updatable = false)
	private String reservationId;

	@Column(name = "organization_id", nullable = false, length = 36, updatable = false)
	private String organizationId;

	@Column(name = "project_id", nullable = false, length = 36, updatable = false)
	private String projectId;

	// 정산 요청의 Idempotency-Key와 본문 지문.
	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	// 정산 종류.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30, updatable = false)
	private SettlementAction action;

	// 이 정산 직전 상태와 직후 상태.
	@Enumerated(EnumType.STRING)
	@Column(name = "previous_state", nullable = false, length = 30, updatable = false)
	private ReservationState previousState;

	@Enumerated(EnumType.STRING)
	@Column(name = "resulting_state", nullable = false, length = 30, updatable = false)
	private ReservationState resultingState;

	// 이 정산으로 비용이 된 금액과 예산으로 돌려준 금액. 해당 없으면 null.
	@Column(name = "committed_cost_usd", precision = 18, scale = 6, updatable = false)
	private BigDecimal committedCostUsd;

	@Column(name = "released_cost_usd", precision = 18, scale = 6, updatable = false)
	private BigDecimal releasedCostUsd;

	// 클라이언트가 알려준 정산 사건 시각(UTC).
	@Column(name = "occurred_at", nullable = false, updatable = false)
	private LocalDateTime occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 적용한 정산을 기록한다.
	 */
	public static BudgetReservationSettlement record(BudgetReservation reservation, SettlementCommand command,
			ReservationState previousState, BigDecimal committedCostUsd, BigDecimal releasedCostUsd) {
		BudgetReservationSettlement settlement = new BudgetReservationSettlement();
		settlement.id = UUID.randomUUID().toString();
		settlement.reservationId = reservation.getId();
		settlement.organizationId = reservation.getOrganizationId();
		settlement.projectId = reservation.getProjectId();
		settlement.idempotencyKey = command.idempotencyKey();
		settlement.requestFingerprint = command.fingerprint();
		settlement.action = command.action();
		settlement.previousState = previousState;
		settlement.resultingState = reservation.getState();
		settlement.committedCostUsd = committedCostUsd;
		settlement.releasedCostUsd = releasedCostUsd;
		settlement.occurredAt = utc(command.occurredAt());
		settlement.createdAt = LocalDateTime.now(ZoneOffset.UTC);
		return settlement;
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
