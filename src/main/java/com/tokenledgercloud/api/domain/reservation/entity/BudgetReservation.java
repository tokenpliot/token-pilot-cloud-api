package com.tokenledgercloud.api.domain.reservation.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ReservationCommand;

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
 * budget_reservations 테이블의 한 행. ENFORCE + ALLOW 판정으로 잡아둔 예산 예약 하나다.
 * 상태는 {@link ReservationState}의 전이 규칙으로만 바뀐다. 정산 요청 하나하나는 {@link BudgetReservationSettlement}에 남는다.
 */
// JPA 엔티티임을 선언한다.
@Entity
// 테이블 이름, 멱등성 UNIQUE 제약, 만료 조회용 인덱스. (V8 SQL과 같다)
@Table(name = "budget_reservations",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_reservation_idempotency", columnNames = {"project_id", "idempotency_key"})
		},
		indexes = {
				@Index(name = "idx_reservation_expiry", columnList = "state, expires_at")
		})
// 모든 필드의 getter를 만든다.
@Getter
// JPA가 쓰는 기본 생성자. 외부에서는 쓰지 못하게 protected로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetReservation {

	// 기본키 = 클라이언트에 주는 reservationId. UUID 문자열(36자).
	@Id
	@Column(length = 36, updatable = false)
	private String id;

	// 조직, 프로젝트, 환경. 프로젝트 키 인증 결과와 요청 본문에서 온다.
	@Column(name = "organization_id", nullable = false, length = 36, updatable = false)
	private String organizationId;

	@Column(name = "project_id", nullable = false, length = 36, updatable = false)
	private String projectId;

	@Column(nullable = false, length = 20, updatable = false)
	private String environment;

	// 예산 기간과, 금액을 잡아둔 두 카운터(프로젝트 전체, 환경). 정산은 반드시 같은 두 카운터에 반영한다.
	@Column(name = "budget_period", nullable = false, length = 7, updatable = false)
	private String budgetPeriod;

	@Column(name = "project_counter_id", nullable = false, length = 36, updatable = false)
	private String projectCounterId;

	@Column(name = "environment_counter_id", nullable = false, length = 36, updatable = false)
	private String environmentCounterId;

	// 판정 요청의 Idempotency-Key와 요청 지문. 같은 키에 다른 지문이 오면 409다.
	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_id", nullable = false, length = 128, updatable = false)
	private String requestId;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	// 호출할 공급자와 모델.
	@Column(nullable = false, length = 128, updatable = false)
	private String provider;

	@Column(nullable = false, length = 128, updatable = false)
	private String model;

	// 예상 토큰 수. 클라이언트가 안 보내면 null.
	@Column(name = "estimated_prompt_tokens", updatable = false)
	private Long estimatedPromptTokens;

	@Column(name = "estimated_completion_tokens", updatable = false)
	private Long estimatedCompletionTokens;

	// 예약 시점의 예상 부채(USD). 정산 전까지 두 카운터의 held_usd에 들어 있다.
	@Column(name = "reserved_cost_usd", nullable = false, precision = 18, scale = 6, updatable = false)
	private BigDecimal reservedCostUsd;

	// 예약할 때 적용한 한도(USD). null은 그 범위에 활성 예산이 없었다는 뜻이다. 나중에 정책이 바뀌어도 왜 허용됐는지 남긴다.
	@Column(name = "project_budget_limit_usd", precision = 18, scale = 6, updatable = false)
	private BigDecimal projectBudgetLimitUsd;

	@Column(name = "environment_budget_limit_usd", precision = 18, scale = 6, updatable = false)
	private BigDecimal environmentBudgetLimitUsd;

	// 통화. 현재는 USD만 허용한다.
	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	// #14 요청 context. 예약 시점의 프로젝트·환경 정책 스냅샷과 가격 스냅샷을 가리킨다.
	@Column(name = "policy_request_context_id", length = 36, updatable = false)
	private String policyRequestContextId;

	// 현재 상태.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReservationState state;

	// 지금까지 비용으로 확정된 금액과 예산으로 돌려준 금액. 종료 상태가 되기 전에는 null이다.
	@Column(name = "committed_cost_usd", precision = 18, scale = 6)
	private BigDecimal committedCostUsd;

	@Column(name = "released_cost_usd", precision = 18, scale = 6)
	private BigDecimal releasedCostUsd;

	// 이 시각(UTC)이 지나도록 정산이 없으면 만료 회수 대상이다.
	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 새 예약을 RESERVED 상태로 만든다. 카운터에 금액을 잡는 것은 호출자(서비스)가 같은 트랜잭션에서 한다.
	 */
	public static BudgetReservation reserve(ReservationCommand command, String projectCounterId,
			String environmentCounterId) {
		if (projectCounterId == null || environmentCounterId == null) {
			throw new IllegalArgumentException("both counter ids are required");
		}
		BudgetReservation reservation = new BudgetReservation();
		reservation.id = UUID.randomUUID().toString();
		reservation.organizationId = command.organizationId();
		reservation.projectId = command.projectId();
		reservation.environment = command.environment();
		reservation.budgetPeriod = command.budgetPeriod();
		reservation.projectCounterId = projectCounterId;
		reservation.environmentCounterId = environmentCounterId;
		reservation.idempotencyKey = command.idempotencyKey();
		reservation.requestId = command.requestId();
		reservation.requestFingerprint = command.fingerprint();
		reservation.provider = command.provider();
		reservation.model = command.model();
		reservation.estimatedPromptTokens = command.estimatedPromptTokens();
		reservation.estimatedCompletionTokens = command.estimatedCompletionTokens();
		reservation.reservedCostUsd = command.reservedCostUsd();
		reservation.projectBudgetLimitUsd = command.projectBudgetLimitUsd();
		reservation.environmentBudgetLimitUsd = command.environmentBudgetLimitUsd();
		reservation.currency = "USD";
		reservation.policyRequestContextId = command.policyRequestContextId();
		reservation.state = ReservationState.RESERVED;
		reservation.expiresAt = command.expiresAt();
		reservation.createdAt = LocalDateTime.now(ZoneOffset.UTC);
		reservation.updatedAt = reservation.createdAt;
		return reservation;
	}

	/**
	 * 호출이 시작됐다고 표시한다. (RESERVED → IN_FLIGHT)
	 */
	public void markInFlight() {
		moveTo(ReservationState.IN_FLIGHT);
	}

	/**
	 * 정산 결과로 상태와 확정·반환 금액을 바꾼다. 상태 전이가 규칙에 맞는지는 여기서 한 번 더 확인한다.
	 * 금액이 null이면 기존 값을 그대로 둔다. (RECONCILIATION_REQUIRED는 아직 아무것도 확정하지 않는다)
	 */
	public void settle(ReservationState target, BigDecimal committedCostUsd, BigDecimal releasedCostUsd) {
		moveTo(target);
		if (committedCostUsd != null) {
			this.committedCostUsd = committedCostUsd;
		}
		if (releasedCostUsd != null) {
			this.releasedCostUsd = releasedCostUsd;
		}
	}

	// 규칙에 없는 전이는 버그이므로 예외를 낸다. (서비스가 먼저 걸러서 409로 바꾼다)
	private void moveTo(ReservationState target) {
		if (!state.canTransitionTo(target)) {
			throw new IllegalStateException("illegal reservation transition " + state + " -> " + target);
		}
		this.state = target;
		this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
	}
}
