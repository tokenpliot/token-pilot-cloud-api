package com.tokenledgercloud.api.domain.reservation.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * 만료 예약 회수(이슈 #17). expires_at이 지나도록 정산이 오지 않은 예약을 닫는다.
 *
 * <ul>
 *   <li>IN_FLIGHT: 호출은 시작됐는데 결과를 모른다 → RECONCILIATION_REQUIRED. 예약 금액을 0원으로 풀지 않고 부채로 남긴다.</li>
 *   <li>RESERVED: ADR 0001은 RELEASED("호출 전 취소·만료")지만, 지금 계약에는 호출 시작을 알리는 API가 없어서
 *       RESERVED여도 실제로는 호출됐을 수 있다. 그래서 기본값은 보수적으로 RECONCILIATION_REQUIRED이고,
 *       {@code token-pilot.reservation.expired-reserved-action=RELEASE}로 ADR대로 바꿀 수 있다.</li>
 * </ul>
 *
 * <p>상태 변경은 {@link ReservationService#settle}을 그대로 쓴다. 그래서 잠금, 두 카운터, 정산 기록 규칙이 클라이언트 정산과 같다.
 * 회수의 정산 키는 {@code exp:<reservationId>}, 사건 시각은 예약의 expires_at이다. 여러 서버가 동시에 회수해도
 * 같은 본문이라 한 번만 적용되고 나머지는 재전송(duplicate)이 된다. 회수 뒤에 늦게 온 COMMIT은 정상 정산된다.
 *
 * <p>아직 스케줄러나 API와 연결하지 않았다. 운영 API와 주기 실행은 후속 작업이다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
public class ReservationExpiryService {

	// 회수 정산의 Idempotency-Key 접두사. (클라이언트 SDK는 stl:로 시작하므로 겹치지 않는다)
	public static final String KEY_PREFIX = "exp:";
	// 결과가 아직 정해지지 않아 회수 대상이 되는 상태.
	private static final EnumSet<ReservationState> OPEN_STATES =
			EnumSet.of(ReservationState.RESERVED, ReservationState.IN_FLIGHT);

	private final BudgetReservationRepository reservationRepository;
	private final ReservationService reservationService;
	// 만료된 RESERVED 예약에 적용할 정산 종류. RELEASE 또는 RECONCILIATION_REQUIRED.
	private final SettlementAction expiredReservedAction;

	public ReservationExpiryService(BudgetReservationRepository reservationRepository,
			ReservationService reservationService,
			@Value("${token-pilot.reservation.expired-reserved-action:RECONCILIATION_REQUIRED}")
			SettlementAction expiredReservedAction) {
		if (expiredReservedAction != SettlementAction.RELEASE
				&& expiredReservedAction != SettlementAction.RECONCILIATION_REQUIRED) {
			throw new IllegalArgumentException(
					"token-pilot.reservation.expired-reserved-action must be RELEASE or RECONCILIATION_REQUIRED");
		}
		this.reservationRepository = reservationRepository;
		this.reservationService = reservationService;
		this.expiredReservedAction = expiredReservedAction;
	}

	/**
	 * nowUtc 기준으로 만료된 예약을 오래된 순서로 최대 limit개 닫는다.
	 */
	public ExpiryResult expire(LocalDateTime nowUtc, int limit) {
		return expire(nowUtc, limit, expiredReservedAction);
	}

	// 만료된 RESERVED에 적용할 정산 종류를 직접 받는다. (설정값과 무관하게 두 경우를 테스트하기 위함)
	ExpiryResult expire(LocalDateTime nowUtc, int limit, SettlementAction actionForReserved) {
		if (nowUtc == null || limit <= 0) {
			throw new IllegalArgumentException("nowUtc is required and limit must be positive");
		}
		List<String> ids = reservationRepository.findExpiredIds(OPEN_STATES, nowUtc, PageRequest.of(0, limit));
		int released = 0;
		int reconciliationRequired = 0;
		int skipped = 0;
		for (String id : ids) {
			// 각 예약은 독립 트랜잭션으로 닫는다. 하나가 실패해도 나머지는 계속한다.
			SettlementResult result = expireOne(id, actionForReserved);
			if (result == null || result.duplicate()) {
				skipped++;
			} else if (result.state() == ReservationState.RELEASED) {
				released++;
			} else {
				reconciliationRequired++;
			}
		}
		return new ExpiryResult(released, reconciliationRequired, skipped);
	}

	// 예약 하나를 닫는다. 그 사이 다른 요청이 먼저 처리했으면 null.
	private SettlementResult expireOne(String reservationId, SettlementAction actionForReserved) {
		BudgetReservation reservation = reservationRepository.findById(reservationId).orElse(null);
		if (reservation == null || !OPEN_STATES.contains(reservation.getState())) {
			return null;
		}
		SettlementAction action = reservation.getState() == ReservationState.IN_FLIGHT
				? SettlementAction.RECONCILIATION_REQUIRED
				: actionForReserved;
		SettlementCommand command = new SettlementCommand(reservation.getId(), reservation.getProjectId(),
				KEY_PREFIX + reservation.getId(), reservation.getRequestId(), action, null, null,
				reservation.getExpiresAt().toInstant(ZoneOffset.UTC));
		try {
			return reservationService.settle(command);
		} catch (ApiException exception) {
			// 클라이언트 정산이 먼저 와서 종료 상태가 됐거나(CONFLICT), 잠금을 못 얻었다(재시도 가능).
			// 둘 다 다음 회수 때 다시 보면 되므로 건너뛴다.
			if (exception.getErrorCode() == ErrorCode.CONFLICT
					|| exception.getErrorCode() == ErrorCode.INGESTION_RETRY_LATER
					|| exception.getErrorCode() == ErrorCode.IDEMPOTENCY_CONFLICT) {
				return null;
			}
			throw exception;
		}
	}
}
