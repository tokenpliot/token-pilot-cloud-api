package com.tokenledgercloud.api.domain.reservation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;

import jakarta.persistence.LockModeType;

/**
 * 예산 예약 저장소.
 */
public interface BudgetReservationRepository extends JpaRepository<BudgetReservation, String> {

	// 판정 요청의 멱등성 키로 예약을 찾는다. 고유 범위는 (프로젝트, 엔드포인트)라서 환경은 조건에 없다. 최대 한 건이다.
	Optional<BudgetReservation> findByProjectIdAndIdempotencyKey(String projectId, String idempotencyKey);

	/**
	 * 정산용으로 예약 행을 잠그고 읽는다(SELECT ... FOR UPDATE). 같은 예약의 정산은 이 잠금 덕분에 한 번에 하나씩만 진행된다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from BudgetReservation r where r.id = :id")
	Optional<BudgetReservation> findByIdForUpdate(@Param("id") String id);

	// 운영 조회: 프로젝트의 지정 상태 예약을 오래된 것부터 page 크기만큼.
	List<BudgetReservation> findByProjectIdAndStateInOrderByCreatedAtAscIdAsc(String projectId,
			Collection<ReservationState> states, Pageable page);

	/**
	 * 만료 시각이 지났는데 아직 결과가 정해지지 않은(RESERVED, IN_FLIGHT) 예약 id. 오래된 것부터 page 크기만큼 돌려준다.
	 * idx_reservation_expiry (state, expires_at) 인덱스를 탄다.
	 */
	@Query("select r.id from BudgetReservation r where r.state in :states and r.expiresAt <= :now"
			+ " order by r.expiresAt asc, r.id asc")
	List<String> findExpiredIds(@Param("states") Collection<ReservationState> states,
			@Param("now") LocalDateTime now, Pageable page);
}
