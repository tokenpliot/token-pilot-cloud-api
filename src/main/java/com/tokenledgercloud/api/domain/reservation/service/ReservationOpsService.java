package com.tokenledgercloud.api.domain.reservation.service;

import java.math.BigDecimal;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.tokenledgercloud.api.domain.policy.PolicyAccess;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.dto.ReconcileRequest;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 운영자 수동 정산(이슈 #17 "수동 대사 운영 API"). 만료 회수나 SDK가 결과를 몰라 RECONCILIATION_REQUIRED로 남긴 예약을
 * 운영자가 청구서 등으로 확인한 뒤 닫는다.
 *
 * <ul>
 *   <li>COMMIT: 확인한 실제 비용(totalCostUsd)으로 확정한다. 토큰 수는 알 수 없어 0으로 기록한다.</li>
 *   <li>WRITE_OFF: 끝내 확인하지 못했다. 예약 금액을 비용으로 확정한다(0원으로 만들지 않는다).</li>
 * </ul>
 *
 * <p>권한은 정책 변경과 같은 OWNER·ADMIN이고 활성 프로젝트여야 한다. 정산 키는 {@code ops:<Idempotency-Key>}라서
 * 같은 키·같은 본문의 재시도는 처음 결과를 돌려주고, 상태 전이·카운터 규칙은 클라이언트 정산과 같다.
 */
@Service
@Slf4j
public class ReservationOpsService {

	// 운영자 수동 정산 키 접두사. 클라이언트 정산에서는 받지 않는다.
	public static final String KEY_PREFIX = "ops:";
	static final int MAX_REASON_LENGTH = 500;

	private final PolicyAccess access;
	private final ReservationService reservationService;
	private final BudgetReservationRepository reservationRepository;
	private final TransactionTemplate authorizationTx;

	public ReservationOpsService(PolicyAccess access, ReservationService reservationService,
			BudgetReservationRepository reservationRepository, PlatformTransactionManager transactionManager) {
		this.access = access;
		this.reservationService = reservationService;
		this.reservationRepository = reservationRepository;
		// 쓰기 권한 확인은 프로젝트 행을 잠그므로 트랜잭션이 필요하다. 정산 트랜잭션과는 분리해서 짧게 끝낸다.
		this.authorizationTx = new TransactionTemplate(transactionManager);
		this.authorizationTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	public SettlementResult reconcile(Authentication authentication, String projectId, String reservationId,
			String idempotencyKey, ReconcileRequest request) {
		// 1. OWNER·ADMIN이고 활성 프로젝트인지 확인한다.
		PolicyAccess.Grant grant = authorizationTx.execute(status -> access.authorize(authentication, projectId, true));
		// 2. 요청을 검사한다.
		validate(idempotencyKey, request);
		// 3. 예약을 찾는다. 다른 프로젝트의 예약은 존재를 알려주지 않는다.
		BudgetReservation reservation = reservationRepository.findById(reservationId)
				.filter(found -> found.getProjectId().equals(projectId))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// 4. 정산 명령을 만들어 적용한다.
		boolean commit = request.action() == SettlementAction.COMMIT;
		SettlementCommand command;
		try {
			command = new SettlementCommand(reservation.getId(), projectId, KEY_PREFIX + idempotencyKey,
					reservation.getRequestId(), request.action(),
					commit ? new SettlementCommand.Usage(0L, 0L, null, null) : null,
					commit ? SettlementCommand.Cost.total(request.totalCostUsd()) : null,
					request.occurredAt());
		} catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, exception.getMessage());
		}
		SettlementResult result = reservationService.settleByOperator(command);
		if (!result.duplicate()) {
			// 감사 로그: 누가, 어느 예약을, 무엇으로, 왜 닫았는지. (금액·키 같은 값은 정산 기록에 이미 있다)
			log.info("Manual reservation settlement: project={}, reservation={}, action={}, member={}, reason={}",
					projectId, reservationId, request.action(), grant.memberId(), request.reason());
		}
		return result;
	}

	// 운영자가 고를 수 있는 정산은 COMMIT과 WRITE_OFF뿐이다. (해제·미확인은 SDK와 만료 회수가 한다)
	private static void validate(String idempotencyKey, ReconcileRequest request) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() < 8
				|| idempotencyKey.length() > 128 - KEY_PREFIX.length()) {
			throw new ApiException(ErrorCode.INVALID_INPUT,
					"Idempotency-Key must be 8.." + (128 - KEY_PREFIX.length()) + " characters");
		}
		if (request == null || (request.action() != SettlementAction.COMMIT
				&& request.action() != SettlementAction.WRITE_OFF)) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "action must be COMMIT or WRITE_OFF");
		}
		BigDecimal cost = request.totalCostUsd();
		if (request.action() == SettlementAction.COMMIT && cost == null) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "totalCostUsd is required for COMMIT");
		}
		if (request.action() == SettlementAction.WRITE_OFF && cost != null) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "totalCostUsd is not allowed for WRITE_OFF");
		}
		if (request.occurredAt() == null) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "occurredAt is required");
		}
		if (request.reason() == null || request.reason().isBlank() || request.reason().length() > MAX_REASON_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "reason must be 1.." + MAX_REASON_LENGTH + " characters");
		}
	}
}
