package com.tokenledgercloud.api.domain.decision;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * LLM 호출 전 판정 API. 기본은 꺼져 있다. {@code token-pilot.decision.enabled=true}로 켜야 이 컨트롤러가 등록되고,
 * 꺼져 있거나 배포 전이면 경로가 없어 404다. SDK는 404를 INDETERMINATE로 다루므로, fail-closed를 고른 고객은 이때 호출이
 * 모두 막힌다. 고객에게 ENFORCE를 안내하기 전에 이 API를 먼저 켠다.
 *
 * <p>정책 거부(DENY_*)와 판정 불가(INDETERMINATE)도 정상 판정이라 모두 200이다. 판정은 권고이며 provider 호출을 막지 않는다.
 */
@ConditionalOnProperty(prefix = "token-pilot.decision", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/control/v1/decisions")
@RequiredArgsConstructor
public class DecisionController {

	private static final String API_KEY_HEADER = "X-API-Key";
	private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final DecisionService decisionService;

	@PostMapping
	public ResponseEntity<ApiResponse<DecisionResponse>> decide(
		@RequestHeader(name = API_KEY_HEADER, required = false) String apiKey,
		@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
		@RequestBody DecisionRequest request
	) {
		// 키가 없는 호출은 본문을 검증하기 전에 401이다.
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHORIZED, "Project API key is required.");
		}
		List<DecisionRequest.FieldViolation> violations = request.violations();
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			violations = new java.util.ArrayList<>(violations);
			violations.add(new DecisionRequest.FieldViolation(IDEMPOTENCY_KEY_HEADER, "must be 8 to 128 characters"));
		}
		if (!violations.isEmpty()) {
			throw new InvalidDecisionRequestException(violations);
		}
		DecisionResponse response = decisionService.decide(apiKey, idempotencyKey, request.toCommand());
		return ResponseEntity.ok(ApiResponse.success("Decision evaluated.", response));
	}
}
