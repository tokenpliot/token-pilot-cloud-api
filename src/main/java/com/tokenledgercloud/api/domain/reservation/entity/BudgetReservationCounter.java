package com.tokenledgercloud.api.domain.reservation.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * budget_reservation_counters 테이블의 한 행. 예산 범위(프로젝트 전체 또는 환경, 예산 기간) 하나의 예약·정산 합계다.
 * environmentKey가 ""이면 프로젝트 전체 범위다. (#14 customer_policies.environment_key와 같은 규칙)
 * 금액은 행을 잠그고(SELECT ... FOR UPDATE) 읽은 트랜잭션 안에서만 {@link #hold}, {@link #settle}로 바꾼다.
 * (여러 서버가 동시에 바꿔도 합계가 맞도록)
 */
// JPA 엔티티임을 선언한다.
@Entity
// 테이블 이름과 범위당 한 행을 보장하는 UNIQUE 제약. (V7 SQL의 uk_reservation_counter_scope와 같다)
@Table(name = "budget_reservation_counters", uniqueConstraints = {
		@UniqueConstraint(name = "uk_reservation_counter_scope",
				columnNames = {"project_id", "environment_key", "budget_period"})
})
// 모든 필드의 getter를 만든다.
@Getter
// JPA가 쓰는 기본 생성자. 외부에서는 쓰지 못하게 protected로 둔다.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetReservationCounter {

	// 기본키. UUID 문자열(36자).
	@Id
	@Column(length = 36, updatable = false)
	private String id;

	// 조직.
	@Column(name = "organization_id", nullable = false, length = 36, updatable = false)
	private String organizationId;

	// 프로젝트.
	@Column(name = "project_id", nullable = false, length = 36, updatable = false)
	private String projectId;

	// 환경 (prod, dev 등). 프로젝트 전체 범위면 "".
	@Column(name = "environment_key", nullable = false, length = 20, updatable = false)
	private String environmentKey;

	// 예산 기간. 월 예산이라 "2026-11" 형식이다.
	@Column(name = "budget_period", nullable = false, length = 7, updatable = false)
	private String budgetPeriod;

	// 아직 정산되지 않은 예약 합계(USD).
	@Column(name = "held_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal heldUsd;

	// 예약 경로로 정산된 비용 합계(USD).
	@Column(name = "committed_usd", nullable = false, precision = 18, scale = 6)
	private BigDecimal committedUsd;

	// 생성 시각.
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	// 마지막으로 금액이 바뀐 시각.
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 금액이 0인 새 범위 행을 만든다.
	 */
	public static BudgetReservationCounter open(String organizationId, String projectId, String environmentKey,
			String budgetPeriod) {
		BudgetReservationCounter counter = new BudgetReservationCounter();
		counter.id = UUID.randomUUID().toString();
		counter.organizationId = organizationId;
		counter.projectId = projectId;
		counter.environmentKey = environmentKey;
		counter.budgetPeriod = budgetPeriod;
		counter.heldUsd = BigDecimal.ZERO.setScale(6);
		counter.committedUsd = BigDecimal.ZERO.setScale(6);
		counter.createdAt = LocalDateTime.now();
		counter.updatedAt = counter.createdAt;
		return counter;
	}

	/**
	 * 정산된 비용 + 미정산 예약 + amount 가 limit 이하인지. limit이 null이면 제한이 없다.
	 * 호출자는 이 행을 잠그고(findByIdForUpdate) 읽은 상태여야 한다.
	 */
	public boolean canHold(BigDecimal amount, BigDecimal limit) {
		return limit == null || heldUsd.add(committedUsd).add(amount).compareTo(limit) <= 0;
	}

	/**
	 * amount를 미정산 예약으로 잡는다. 한도 확인({@link #canHold})은 호출자가 모든 범위에 대해 먼저 한다.
	 */
	public void hold(BigDecimal amount) {
		heldUsd = heldUsd.add(amount);
		updatedAt = LocalDateTime.now();
	}

	/**
	 * 잡아둔 금액 release를 풀고 commit을 비용으로 더한다. 정산 하나에 한 번만 호출한다.
	 * 호출자는 이 행을 잠그고 읽은 상태여야 한다.
	 */
	public void settle(BigDecimal release, BigDecimal commit) {
		if (heldUsd.compareTo(release) < 0) {
			// held가 예약 금액보다 작다는 것은 장부가 이미 어긋났다는 뜻이다. 트랜잭션을 되돌리게 예외를 낸다.
			throw new IllegalStateException("reservation counter " + id + " holds " + heldUsd
					+ " but " + release + " was to be released");
		}
		heldUsd = heldUsd.subtract(release);
		committedUsd = committedUsd.add(commit);
		updatedAt = LocalDateTime.now();
	}
}
