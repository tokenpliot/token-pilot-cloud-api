package com.tokenledgercloud.api.domain.reservation.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.policy.PolicyAccess;
import com.tokenledgercloud.api.domain.reservation.dto.ReservationDetailView;
import com.tokenledgercloud.api.domain.reservation.dto.ReservationView;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementView;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 운영자가 예약을 조회한다(이슈 #17 운영 API). 권한은 정책 API와 같다:
 * 프로젝트 조직의 OWNER·ADMIN·OPERATOR·VIEWER가 볼 수 있고, 다른 조직 프로젝트는 존재도 알려주지 않는다(404).
 */
@Service
@RequiredArgsConstructor
public class ReservationQueryService {

	// state를 안 주면 아직 끝나지 않은(예산을 잡고 있는) 예약을 보여준다. 운영자가 가장 먼저 봐야 할 것들이다.
	static final Set<ReservationState> OPEN_STATES = EnumSet.of(
			ReservationState.RESERVED, ReservationState.IN_FLIGHT, ReservationState.RECONCILIATION_REQUIRED);
	static final int MAX_LIMIT = 500;

	private final PolicyAccess access;
	private final BudgetReservationRepository reservationRepository;
	private final BudgetReservationSettlementRepository settlementRepository;

	/**
	 * 프로젝트의 예약을 오래된 것부터 limit개 돌려준다.
	 *
	 * @param states 비어 있으면 RESERVED, IN_FLIGHT, RECONCILIATION_REQUIRED
	 */
	@Transactional(readOnly = true)
	public List<ReservationView> list(Authentication authentication, String projectId, Set<ReservationState> states,
			int limit) {
		access.authorize(authentication, projectId, false);
		if (limit <= 0 || limit > MAX_LIMIT) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "limit must be 1.." + MAX_LIMIT);
		}
		Set<ReservationState> filter = states == null || states.isEmpty() ? OPEN_STATES : states;
		return reservationRepository.findByProjectIdAndStateInOrderByCreatedAtAscIdAsc(projectId, filter,
				PageRequest.of(0, limit)).stream().map(ReservationView::from).toList();
	}

	/**
	 * 예약 한 건과 정산 이력. 다른 프로젝트의 예약 id면 404다.
	 */
	@Transactional(readOnly = true)
	public ReservationDetailView detail(Authentication authentication, String projectId, String reservationId) {
		access.authorize(authentication, projectId, false);
		ReservationView reservation = reservationRepository.findById(reservationId)
				.filter(found -> found.getProjectId().equals(projectId))
				.map(ReservationView::from)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<SettlementView> settlements = settlementRepository
				.findByReservationIdOrderByCreatedAtAscIdAsc(reservationId).stream().map(SettlementView::from).toList();
		return new ReservationDetailView(reservation, settlements);
	}
}
