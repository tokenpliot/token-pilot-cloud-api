package com.tokenledgercloud.api.domain.reservation.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.ReservationResult;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationCounter;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * ENFORCE 예산 예약과 정산(이슈 #17). 여러 서버가 동시에 예약해도 한도를 넘겨 승인하지 않고,
 * 같은 정산이 여러 번 와도 비용을 두 번 세지 않는다.
 *
 * <p>아직 API와 연결하지 않았다. 판정 API(#15, ENFORCE + ALLOW)와 정산 API가 이 서비스를 호출한다.
 * 이 클래스에는 트랜잭션을 걸지 않는다. 각 단계가 {@link ReservationTx}의 독립 트랜잭션이어야
 * 동시 요청이 먼저 커밋한 결과를 다시 읽을 수 있기 때문이다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ReservationService {

	// 프로젝트 전체 범위 카운터의 environmentKey. (#14 customer_policies와 같은 규칙)
	static final String PROJECT_SCOPE = "";

	// 트랜잭션 단위 작업을 맡는 빈.
	private final ReservationTx reservationTx;

	/**
	 * 두 범위(프로젝트 전체, 환경)의 한도 안이면 예약하고, 한쪽이라도 모자라면 granted=false를 돌려준다.
	 * 같은 Idempotency-Key·같은 요청은 기존 예약을 돌려주고(duplicate), 같은 키에 다른 요청은 409다.
	 */
	public ReservationResult reserve(ReservationCommand command) {
		String fingerprint = command.fingerprint();

		// 1. 같은 멱등성 키의 예약이 이미 있으면 그것을 돌려준다. (한도는 다시 따지지 않는다)
		Optional<BudgetReservation> existing = reservationTx.findByKey(command.projectId(), command.idempotencyKey());
		if (existing.isPresent()) {
			return replay(existing.get(), fingerprint);
		}

		// 2. 범위 카운터 두 개를 찾거나 만든 뒤, 한도 안에서 금액을 잡고 예약을 넣는다.
		BudgetReservation candidate = BudgetReservation.reserve(command,
				counterIdFor(command, PROJECT_SCOPE), counterIdFor(command, command.environment()));
		try {
			Optional<BudgetReservation> created = reservationTx.holdAndInsert(candidate);
			if (created.isPresent()) {
				return ReservationResult.created(created.get());
			}
			// 한도가 모자랐다. 단, 같은 키의 요청이 동시에 먼저 예약해서 한도를 채운 경우라면 그 예약을 돌려줘야 한다.
			return reservationTx.findByKey(command.projectId(), command.idempotencyKey())
					.map(stored -> replay(stored, fingerprint))
					.orElseGet(ReservationResult::insufficientBudget);
		} catch (DataIntegrityViolationException exception) {
			// 3. 같은 키가 동시에 들어와 다른 요청이 먼저 넣었다. 잡았던 금액은 이미 되돌아갔다. 다시 읽어서 비교한다.
			Optional<BudgetReservation> raced = reservationTx.findByKey(command.projectId(), command.idempotencyKey());
			if (raced.isPresent()) {
				return replay(raced.get(), fingerprint);
			}
			throw exception;
		} catch (PessimisticLockingFailureException exception) {
			// 데드락·락 타임아웃. 같은 요청을 다시 보내면 된다. (재시도 가능 503)
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		}
	}

	/**
	 * 호출이 시작됐다고 표시한다. (RESERVED → IN_FLIGHT)
	 */
	public BudgetReservation markInFlight(String reservationId, String projectId) {
		try {
			return reservationTx.markInFlight(reservationId, projectId);
		} catch (PessimisticLockingFailureException exception) {
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		}
	}

	/**
	 * 예약을 정산하거나 해제한다. 같은 Idempotency-Key·같은 본문의 재전송은 처음 결과를 돌려주고(duplicate),
	 * 같은 키에 다른 본문은 409 IDEMPOTENCY_CONFLICT, 종료 상태에 다른 정산은 409 CONFLICT다.
	 * {@code exp:}로 시작하는 키는 만료 회수 전용이라 400이다. 클라이언트가 다른 예약의 회수 키를 먼저 차지하면
	 * 그 예약은 회수 기록을 남길 수 없어 영영 닫히지 않는다.
	 */
	public SettlementResult settle(SettlementCommand command) {
		if (command.idempotencyKey().startsWith(ReservationExpiryService.KEY_PREFIX)) {
			throw new ApiException(ErrorCode.INVALID_INPUT,
					"Idempotency-Key prefix " + ReservationExpiryService.KEY_PREFIX + " is reserved for server expiry");
		}
		try {
			return reservationTx.settle(command);
		} catch (DataIntegrityViolationException exception) {
			// 같은 정산 키가 다른 예약에 동시에 쓰여 기록 INSERT가 부딪혔다. 먼저 커밋된 쪽과 비교한다. (지문이 달라서 409)
			return reservationTx.settle(command);
		} catch (PessimisticLockingFailureException exception) {
			throw new ApiException(ErrorCode.INGESTION_RETRY_LATER);
		}
	}

	// 범위 카운터 id. 없으면 만든다. 동시에 만들면 한쪽은 UNIQUE 오류가 나므로 다시 읽는다.
	private String counterIdFor(ReservationCommand command, String environmentKey) {
		Optional<BudgetReservationCounter> found = reservationTx.findCounter(
				command.projectId(), environmentKey, command.budgetPeriod());
		if (found.isPresent()) {
			return found.get().getId();
		}
		try {
			return reservationTx.insertCounter(BudgetReservationCounter.open(command.organizationId(),
					command.projectId(), environmentKey, command.budgetPeriod())).getId();
		} catch (DataIntegrityViolationException exception) {
			return reservationTx.findCounter(command.projectId(), environmentKey, command.budgetPeriod())
					.orElseThrow(() -> exception)
					.getId();
		}
	}

	// 같은 키로 이미 있는 예약. 요청 지문이 같으면 그 예약을, 다르면 409를 돌려준다.
	private ReservationResult replay(BudgetReservation stored, String fingerprint) {
		if (!stored.getRequestFingerprint().equals(fingerprint)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return ReservationResult.existing(stored);
	}
}
