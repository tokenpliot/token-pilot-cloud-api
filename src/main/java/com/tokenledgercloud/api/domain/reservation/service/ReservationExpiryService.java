package com.tokenledgercloud.api.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;

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
 * <p>상태 확인과 정산 적용은 {@link ReservationTx#expire}가 예약 행을 잠근 채 한 번에 한다. 그래서 그 사이 IN_FLIGHT가 된 예약을
 * RELEASE로 푸는 일이 없고, 두 카운터와 정산 기록 규칙은 클라이언트 정산과 같다. 회수의 정산 키는 {@code exp:<reservationId>},
 * 사건 시각은 예약의 expires_at이다. 이 접두사는 서버 전용이라 클라이언트 정산에서는 거부한다({@link ReservationService#settle}).
 * 여러 서버가 동시에 회수해도 늦은 쪽은 잠금 뒤 이미 닫힌 상태를 보고 건너뛴다. 회수 뒤에 늦게 온 COMMIT은 정상 정산된다.
 *
 * <p>아직 스케줄러나 API와 연결하지 않았다. 운영 API와 주기 실행은 후속 작업이다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
public class ReservationExpiryService {

	// 회수 정산의 Idempotency-Key 접두사. 서버 전용이라 클라이언트 정산 키로는 받지 않는다. (SDK는 stl:를 쓴다)
	public static final String KEY_PREFIX = "exp:";
	// 결과가 아직 정해지지 않아 회수 대상이 되는 상태.
	private static final EnumSet<ReservationState> OPEN_STATES =
			EnumSet.of(ReservationState.RESERVED, ReservationState.IN_FLIGHT);

	private final BudgetReservationRepository reservationRepository;
	private final ReservationTx reservationTx;
	// 만료된 RESERVED 예약에 적용할 정산 종류. RELEASE 또는 RECONCILIATION_REQUIRED.
	private final SettlementAction expiredReservedAction;

	public ReservationExpiryService(BudgetReservationRepository reservationRepository,
			ReservationTx reservationTx,
			@Value("${token-pilot.reservation.expired-reserved-action:RECONCILIATION_REQUIRED}")
			SettlementAction expiredReservedAction) {
		if (expiredReservedAction != SettlementAction.RELEASE
				&& expiredReservedAction != SettlementAction.RECONCILIATION_REQUIRED) {
			throw new IllegalArgumentException(
					"token-pilot.reservation.expired-reserved-action must be RELEASE or RECONCILIATION_REQUIRED");
		}
		this.reservationRepository = reservationRepository;
		this.reservationTx = reservationTx;
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
			if (result == null) {
				skipped++;
			} else if (result.state() == ReservationState.RELEASED) {
				released++;
			} else {
				reconciliationRequired++;
			}
		}
		return new ExpiryResult(released, reconciliationRequired, skipped);
	}

	// 예약 하나를 닫는다. 그 사이 다른 요청이 먼저 닫았거나 잠금을 얻지 못했으면 null. (다음 회수 때 다시 본다)
	private SettlementResult expireOne(String reservationId, SettlementAction actionForReserved) {
		try {
			return reservationTx.expire(reservationId, actionForReserved).orElse(null);
		} catch (PessimisticLockingFailureException exception) {
			return null;
		}
	}
}
