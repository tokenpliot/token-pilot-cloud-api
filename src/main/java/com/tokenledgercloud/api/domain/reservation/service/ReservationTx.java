package com.tokenledgercloud.api.domain.reservation.service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationCounter;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationSettlement;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 예약·정산의 트랜잭션 단위. 별도 빈으로 둬서 호출이 항상 트랜잭션 프록시를 거치게 한다.
 *
 * <p>쓰기 트랜잭션은 #14와 같이 READ_COMMITTED로 실행한다. 잠금을 기다린 뒤 읽는 값이 항상 방금 커밋된 최신 값이어야 하기 때문이다.
 *
 * <p>잠금 순서: 예약은 프로젝트 카운터 → 환경 카운터를 잠그고 새 행을 넣는다.
 * 정산은 예약 행 → 프로젝트 카운터 → 환경 카운터 순서다. 예약 쪽이 기존 예약 행을 기다리는 일이 없어서 순환 대기가 생기지 않는다.
 */
// 스프링 빈으로 등록한다.
@Component
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ReservationTx {

	private final BudgetReservationRepository reservationRepository;
	private final BudgetReservationCounterRepository counterRepository;
	private final BudgetReservationSettlementRepository settlementRepository;

	// 새 트랜잭션에서 읽는다. 동시에 들어온 다른 요청이 방금 커밋한 행도 보려면 새 트랜잭션이어야 한다.
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<BudgetReservation> findByKey(String projectId, String idempotencyKey) {
		return reservationRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<BudgetReservationCounter> findCounter(String projectId, String environmentKey, String budgetPeriod) {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(projectId, environmentKey,
				budgetPeriod);
	}

	// 새 트랜잭션에서 카운터 행을 만든다. 동시에 같은 범위를 만들면 UNIQUE 오류가 호출자 트랜잭션을 망가뜨리지 않게 하려는 것이다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BudgetReservationCounter insertCounter(BudgetReservationCounter counter) {
		return counterRepository.saveAndFlush(counter);
	}

	/**
	 * 두 범위(프로젝트 전체, 환경)의 한도를 모두 만족하면 둘 다에 금액을 잡고 예약 행을 넣는다. 한 트랜잭션이라 함께 커밋되거나 함께 취소된다.
	 * 같은 멱등성 키가 동시에 들어오면 늦은 쪽은 INSERT에서 UNIQUE 오류가 나고, 잡았던 금액도 같이 되돌아간다.
	 *
	 * @return 한쪽이라도 한도가 모자라면 아무것도 바꾸지 않고 비어 있다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public Optional<BudgetReservation> holdAndInsert(BudgetReservation reservation) {
		BudgetReservationCounter project = lockCounter(reservation.getProjectCounterId());
		BudgetReservationCounter environment = lockCounter(reservation.getEnvironmentCounterId());
		BigDecimal amount = reservation.getReservedCostUsd();
		if (!project.canHold(amount, reservation.getProjectBudgetLimitUsd())
				|| !environment.canHold(amount, reservation.getEnvironmentBudgetLimitUsd())) {
			return Optional.empty();
		}
		project.hold(amount);
		environment.hold(amount);
		return Optional.of(reservationRepository.saveAndFlush(reservation));
	}

	/**
	 * 호출이 시작됐다고 표시한다. 이미 IN_FLIGHT면 그대로 둔다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public BudgetReservation markInFlight(String reservationId, String projectId) {
		BudgetReservation reservation = lock(reservationId, projectId);
		if (reservation.getState() == ReservationState.IN_FLIGHT) {
			return reservation;
		}
		if (reservation.getState() != ReservationState.RESERVED) {
			throw new ApiException(ErrorCode.CONFLICT, "reservation is " + reservation.getState());
		}
		reservation.markInFlight();
		return reservation;
	}

	/**
	 * 정산 한 건을 적용한다. 예약 행을 잠근 채로 상태, 두 카운터, 정산 기록을 함께 바꾸므로
	 * 같은 정산이 동시에 여러 번 와도 카운터에는 한 번만 반영되고 나머지는 재전송(duplicate)으로 끝난다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public SettlementResult settle(SettlementCommand command) {
		BudgetReservation reservation = lock(command.reservationId(), command.projectId());

		// 1. 같은 Idempotency-Key로 이미 적용한 정산이 있으면: 본문이 같으면 그때 결과, 다르면 409.
		Optional<SettlementResult> replayed = replay(command);
		if (replayed.isPresent()) {
			return replayed.get();
		}
		if (!reservation.getRequestId().equals(command.requestId())) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "requestId does not match the reservation");
		}
		return apply(reservation, command);
	}

	/**
	 * 만료 예약 하나를 닫는다. 예약 행을 잠근 뒤의 상태로 정산 종류를 정한다. 잠그기 전에 읽은 상태로 정하면
	 * 그 사이 IN_FLIGHT가 된 예약을 RELEASE로 풀어, 실행 중인 호출의 예산을 돌려줄 수 있다.
	 * IN_FLIGHT는 항상 RECONCILIATION_REQUIRED(부채 유지), RESERVED는 actionForReserved다.
	 *
	 * @return 이미 닫혀 있으면(클라이언트 정산이나 다른 서버의 회수가 먼저 커밋) 비어 있다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Optional<SettlementResult> expire(String reservationId, SettlementAction actionForReserved) {
		Optional<BudgetReservation> locked = reservationRepository.findByIdForUpdate(reservationId);
		if (locked.isEmpty()) {
			return Optional.empty();
		}
		BudgetReservation reservation = locked.get();
		ReservationState state = reservation.getState();
		if (state != ReservationState.RESERVED && state != ReservationState.IN_FLIGHT) {
			return Optional.empty();
		}
		SettlementAction action = state == ReservationState.IN_FLIGHT
				? SettlementAction.RECONCILIATION_REQUIRED
				: actionForReserved;
		SettlementCommand command = new SettlementCommand(reservation.getId(), reservation.getProjectId(),
				ReservationExpiryService.KEY_PREFIX + reservation.getId(), reservation.getRequestId(), action, null, null,
				reservation.getExpiresAt().toInstant(ZoneOffset.UTC));
		return Optional.of(apply(reservation, command));
	}

	// 잠근 예약에 정산 한 건을 적용한다. 상태 전이 검사, 두 카운터, 예약 상태, 정산 기록을 함께 바꾼다.
	private SettlementResult apply(BudgetReservation reservation, SettlementCommand command) {
		// 2. 상태 전이가 규칙에 맞는지 본다. RESERVED에서 바로 온 COMMIT/RECONCILIATION_REQUIRED는
		//    호출이 이미 일어난 것이므로 IN_FLIGHT를 거친 것으로 본다.
		ReservationState from = reservation.getState();
		SettlementAction action = command.action();
		boolean viaInFlight = from == ReservationState.RESERVED
				&& (action == SettlementAction.COMMIT || action == SettlementAction.RECONCILIATION_REQUIRED);
		ReservationState effectiveFrom = viaInFlight ? ReservationState.IN_FLIGHT : from;
		if (!effectiveFrom.canTransitionTo(action.target())) {
			// 종료 상태에 다른 정산을 요구하는 경우 등. (ADR 0001 §6, yaml: 409)
			throw new ApiException(ErrorCode.CONFLICT,
					"reservation is " + from + " and cannot be settled with " + action);
		}

		// 3. 카운터에 반영할 금액을 정한다. 잡아둔 금액(reserved)은 종료 상태로 갈 때 전부 풀고, 확정 비용을 따로 더한다.
		BigDecimal reserved = reservation.getReservedCostUsd();
		BigDecimal zero = BigDecimal.ZERO.setScale(6);
		BigDecimal release;
		BigDecimal commit;
		BigDecimal committedCost;
		BigDecimal releasedCost;
		switch (action) {
			case COMMIT -> {
				// 금액 기준은 totalCostUsd다. 예약보다 커도 이미 일어난 호출이므로 그대로 확정한다. (초과분은 정산된 비용에 드러난다)
				release = reserved;
				commit = command.totalCostUsd();
				committedCost = commit;
				releasedCost = reserved.subtract(commit).max(zero);
			}
			case RELEASE -> {
				release = reserved;
				commit = zero;
				committedCost = null;
				releasedCost = reserved;
			}
			case RECONCILIATION_REQUIRED -> {
				// 결과를 모르면 0원으로 풀지 않는다. 예약 금액을 미정산 부채로 계속 잡아둔다.
				release = zero;
				commit = zero;
				committedCost = null;
				releasedCost = null;
			}
			case WRITE_OFF -> {
				// 끝내 확인하지 못한 예약은 예약 금액을 비용으로 확정한다. (보수적으로, 0원 처리하지 않는다)
				release = reserved;
				commit = reserved;
				committedCost = reserved;
				releasedCost = zero;
			}
			default -> throw new IllegalStateException("unknown action " + action);
		}
		if (release.signum() != 0 || commit.signum() != 0) {
			// 장부가 어긋나 있으면 counter.settle이 예외를 내고 트랜잭션 전체(예약 상태 포함)가 되돌아간다.
			lockCounter(reservation.getProjectCounterId()).settle(release, commit);
			lockCounter(reservation.getEnvironmentCounterId()).settle(release, commit);
		}

		// 4. 예약 상태를 바꾸고 정산을 기록한다. 같은 키가 다른 예약에 동시에 쓰이면 기록 INSERT가 UNIQUE 오류로 전체를 되돌린다.
		if (viaInFlight) {
			reservation.markInFlight();
		}
		reservation.settle(action.target(), committedCost, releasedCost);
		BudgetReservationSettlement settlement = settlementRepository.saveAndFlush(
				BudgetReservationSettlement.record(reservation, command, from, committedCost, releasedCost));
		return SettlementResult.of(settlement, command.requestId(), false);
	}

	/**
	 * 같은 Idempotency-Key의 정산 기록이 있으면 결과를 돌려준다. 본문 지문이 다르면(occurredAt만 달라도) 409다.
	 * settle 안에서 예약 행을 잠근 뒤에 부르므로, 같은 예약에 동시에 온 같은 정산은 앞선 쪽이 커밋한 기록을 여기서 본다.
	 * 지문에 reservationId가 들어 있어서 같은 키를 다른 예약에 쓰면 409가 된다.
	 */
	private Optional<SettlementResult> replay(SettlementCommand command) {
		Optional<BudgetReservationSettlement> stored = settlementRepository.findByProjectIdAndIdempotencyKey(
				command.projectId(), command.idempotencyKey());
		if (stored.isEmpty()) {
			return Optional.empty();
		}
		if (!stored.get().getRequestFingerprint().equals(command.fingerprint())) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		return Optional.of(SettlementResult.of(stored.get(), command.requestId(), true));
	}

	// 카운터 행을 잠그고 읽는다. 예약 행이 가리키는 카운터는 반드시 있어야 한다.
	private BudgetReservationCounter lockCounter(String counterId) {
		return counterRepository.findByIdForUpdate(counterId)
				.orElseThrow(() -> new IllegalStateException("reservation counter " + counterId + " is missing"));
	}

	// 예약 행을 잠그고 읽는다. 다른 프로젝트의 예약은 존재를 알려주지 않도록 404로 같게 처리한다.
	private BudgetReservation lock(String reservationId, String projectId) {
		return reservationRepository.findByIdForUpdate(reservationId)
				.filter(reservation -> reservation.getProjectId().equals(projectId))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}
}
