package com.tokenledgercloud.api.domain.reservation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationCounter;

import jakarta.persistence.LockModeType;

/**
 * 예약 카운터 저장소. 금액을 바꿀 때는 반드시 {@link #findByIdForUpdate}로 행을 잠그고 읽은 뒤 바꾼다.
 */
public interface BudgetReservationCounterRepository extends JpaRepository<BudgetReservationCounter, String> {

	// (프로젝트, 범위, 기간)의 카운터. environmentKey ""는 프로젝트 전체 범위. UNIQUE 제약이 있어서 최대 한 건이다.
	Optional<BudgetReservationCounter> findByProjectIdAndEnvironmentKeyAndBudgetPeriod(
			String projectId, String environmentKey, String budgetPeriod);

	/**
	 * 카운터 행을 잠그고 읽는다(SELECT ... FOR UPDATE). 같은 범위의 예약·정산은 이 잠금에서 한 줄로 선다.
	 * 앞선 트랜잭션이 커밋한 최신 금액을 읽으므로, 여러 서버가 동시에 예약해도 합계가 한도를 넘지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from BudgetReservationCounter c where c.id = :id")
	Optional<BudgetReservationCounter> findByIdForUpdate(@Param("id") String id);
}
