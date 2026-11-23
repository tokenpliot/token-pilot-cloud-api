package com.tokenledgercloud.api.domain.reservation.controller;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.dto.ReconcileRequest;
import com.tokenledgercloud.api.domain.reservation.dto.ReservationDetailView;
import com.tokenledgercloud.api.domain.reservation.dto.ReservationView;
import com.tokenledgercloud.api.domain.reservation.service.ReservationOpsService;
import com.tokenledgercloud.api.domain.reservation.service.ReservationQueryService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * 예약 운영 API(이슈 #17). 로그인한 회원만 쓸 수 있다(SecurityConfig의 /api/reservations/** 규칙).
 * 조회는 프로젝트 조직의 OWNER·ADMIN·OPERATOR·VIEWER, 수동 정산은 OWNER·ADMIN만 할 수 있다.
 */
@RestController
@RequestMapping("/api/reservations/projects/{projectId}")
@RequiredArgsConstructor
public class ReservationOpsController {

	private final ReservationQueryService queryService;
	private final ReservationOpsService opsService;

	// GET /api/reservations/projects/{projectId}?state=RECONCILIATION_REQUIRED&limit=50
	// state를 안 주면 아직 예산을 잡고 있는 예약(RESERVED, IN_FLIGHT, RECONCILIATION_REQUIRED)을 보여준다.
	@GetMapping
	public ApiResponse<List<ReservationView>> list(Authentication auth, @PathVariable String projectId,
			@RequestParam(required = false) List<String> state,
			@RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.success(queryService.list(auth, projectId, states(state), limit));
	}

	// GET /api/reservations/projects/{projectId}/{reservationId} : 예약 한 건과 정산 이력
	@GetMapping("/{reservationId}")
	public ApiResponse<ReservationDetailView> detail(Authentication auth, @PathVariable String projectId,
			@PathVariable String reservationId) {
		return ApiResponse.success(queryService.detail(auth, projectId, reservationId));
	}

	// POST /api/reservations/projects/{projectId}/{reservationId}/reconcile : 운영자 수동 정산 (COMMIT 또는 WRITE_OFF)
	@PostMapping("/{reservationId}/reconcile")
	public ApiResponse<SettlementResult> reconcile(Authentication auth, @PathVariable String projectId,
			@PathVariable String reservationId,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ReconcileRequest request) {
		return ApiResponse.success(opsService.reconcile(auth, projectId, reservationId, idempotencyKey, request));
	}

	// state 파라미터를 상태 집합으로 바꾼다. 쉼표로 여러 개를 줄 수 있고, 모르는 값은 400이다. (500이 되지 않게 직접 해석한다)
	static Set<ReservationState> states(List<String> values) {
		Set<ReservationState> states = EnumSet.noneOf(ReservationState.class);
		if (values == null) {
			return states;
		}
		for (String value : values) {
			for (String part : value.split(",")) {
				if (part.isBlank()) {
					continue;
				}
				try {
					states.add(ReservationState.valueOf(part.trim().toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException exception) {
					throw new ApiException(ErrorCode.INVALID_INPUT, "unknown reservation state");
				}
			}
		}
		return states;
	}
}
