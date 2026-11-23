package com.tokenledgercloud.api.domain.reservation.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.decision.DecisionRequest.FieldViolation;
import com.tokenledgercloud.api.domain.decision.InvalidDecisionRequestException;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementRequest;
import com.tokenledgercloud.api.domain.reservation.dto.SettlementResponse;
import com.tokenledgercloud.api.domain.reservation.service.ReservationSettlementService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * ENFORCE 예약 정산 API (이슈 #17, control-plane-v1.yaml의 settleReservation). 판정 API와 같이
 * {@code token-pilot.decision.enabled=true}일 때만 등록된다. 예약은 판정이 발급하므로, 판정 API가 꺼져 있으면 정산할 것도 없다.
 *
 * <p>오류 응답은 판정 API와 같은 계약 형식이다(ControlPlaneExceptionHandler, data.outcome 포함).
 */
@ConditionalOnProperty(prefix = "token-pilot.decision", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/control/v1/reservations/{reservationId}/settlements")
@RequiredArgsConstructor
public class ReservationSettlementController {

	private static final String API_KEY_HEADER = "X-API-Key";
	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final ReservationSettlementService settlementService;

	@PostMapping
	public ResponseEntity<ApiResponse<SettlementResponse>> settle(
			@RequestHeader(name = API_KEY_HEADER, required = false) String apiKey,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
			@PathVariable String reservationId,
			@RequestBody SettlementRequest request) {
		// 키가 없는 호출은 본문을 검증하기 전에 401이다. (판정 API와 같은 순서)
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Project API key is required.");
		}
		List<FieldViolation> violations = new ArrayList<>(request.violations());
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			violations.add(new FieldViolation(IDEMPOTENCY_KEY_HEADER, "must be 8 to 128 characters"));
		}
		if (!violations.isEmpty()) {
			throw new InvalidDecisionRequestException(violations);
		}
		SettlementResponse response = settlementService.settle(apiKey, idempotencyKey, reservationId, request);
		String message = response.duplicate() ? "Settlement already applied." : "Reservation settled.";
		return ResponseEntity.ok(ApiResponse.success(message, response));
	}
}
