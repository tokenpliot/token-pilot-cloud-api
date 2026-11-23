package com.tokenledgercloud.api.domain.reservation.service;

import org.springframework.stereotype.Service;

import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementRequest;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementResponse;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservation;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 클라이언트(SDK) 정산 API의 서비스(이슈 #17, control-plane-v1.yaml의 settleReservation).
 *
 * <p>검사 순서: 프로젝트 API 키(401, 만료 401, 비활성 프로젝트 403) → 예약이 키의 프로젝트 것인지(아니면 404, 다른 프로젝트의
 * 예약은 존재를 알려주지 않음) → 키의 환경 제한(403) → 정산(재전송 duplicate, 같은 키에 다른 본문 409, 종료 상태에 다른 결과 409).
 * 상태 전이·카운터·멱등성 규칙은 {@link ReservationService#settle}과 같다.
 */
@Service
@RequiredArgsConstructor
public class ReservationSettlementService {

	private final ProjectApiKeyAuthenticator authenticator;
	private final BudgetReservationRepository reservationRepository;
	private final ReservationService reservationService;

	/**
	 * @param request {@link SettlementRequest#violations()}가 비어 있는 요청
	 */
	public SettlementResponse settle(String rawApiKey, String idempotencyKey, String reservationId,
			SettlementRequest request) {
		AuthenticatedProjectApiKey key = authenticator.authenticateForProjectResource(rawApiKey);
		BudgetReservation reservation = reservationRepository.findById(reservationId)
				.filter(found -> found.getProjectId().equals(key.projectId()))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Reservation was not found."));
		ProjectApiKeyAuthenticator.requireEnvironment(key, reservation.getEnvironment());
		SettlementResult result;
		try {
			result = reservationService.settle(request.toCommand(reservationId, key.projectId(), idempotencyKey));
		} catch (IllegalArgumentException exception) {
			// 계약 검사를 통과했지만 도메인 규칙(예: 금액 자릿수)에 맞지 않는 값. 값은 메시지에 싣지 않는다.
			throw new ApiException(ErrorCode.INVALID_INPUT, "Settlement does not match the contract.");
		}
		return SettlementResponse.from(result, request.requestId);
	}
}
