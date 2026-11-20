package com.tokenledgercloud.api.domain.reservation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationSettlement;

/**
 * 정산 기록 저장소. 기록은 추가만 한다.
 */
public interface BudgetReservationSettlementRepository extends JpaRepository<BudgetReservationSettlement, String> {

	// 정산 요청의 멱등성 키로 기록을 찾는다. UNIQUE 제약이 있어서 최대 한 건이다.
	Optional<BudgetReservationSettlement> findByProjectIdAndIdempotencyKey(String projectId, String idempotencyKey);
}
