package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassActiveResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassGrantResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * break-glass API. (이슈 #19)
 * 인증·권한 확인은 서비스가 #14의 PolicyAccess로 한다. SecurityConfig는 팀 공용이라 건드리지 않는다.
 */
@RestController
@RequestMapping("/api/rollout/break-glass")
@RequiredArgsConstructor
public class BreakGlassController {

	private final BreakGlassService breakGlassService;
	private final BreakGlassUsageService breakGlassUsageService;

	// POST /api/rollout/break-glass (OWNER/ADMIN/OPERATOR)
	@PostMapping
	public ResponseEntity<ApiResponse<BreakGlassGrantResponse>> issue(
			Authentication authentication,
			@Valid @RequestBody BreakGlassIssueRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("break-glass 발급 성공",
				breakGlassService.issue(authentication, request, nowUtc())));
	}

	// POST /api/rollout/break-glass/{grantId}/revoke (OWNER/ADMIN/OPERATOR)
	@PostMapping("/{grantId}/revoke")
	public ResponseEntity<ApiResponse<BreakGlassGrantResponse>> revoke(
			Authentication authentication,
			@PathVariable String grantId,
			@Valid @RequestBody BreakGlassRevokeRequest request) {
		return ResponseEntity.ok(ApiResponse.success("break-glass 취소 성공",
				breakGlassService.revoke(authentication, grantId, request, nowUtc())));
	}

	// GET /api/rollout/break-glass/active?projectId=...&environment=... (Client SDK 조회용, 인증 없음)
	@GetMapping("/active")
	public ResponseEntity<ApiResponse<BreakGlassActiveResponse>> active(
			@RequestParam String projectId,
			@RequestParam String environment) {
		return ResponseEntity.ok(ApiResponse.success("유효한 break-glass 조회 성공",
				breakGlassService.getActive(projectId, environment, nowUtc())));
	}

	// GET /api/rollout/break-glass?projectId=...&environment=...&limit=50 (사후 감사 이력, 조직 멤버)
	@GetMapping
	public ResponseEntity<ApiResponse<List<BreakGlassGrantResponse>>> history(
			Authentication authentication,
			@RequestParam String projectId,
			@RequestParam(required = false) String environment,
			@RequestParam(required = false) Integer limit) {
		return ResponseEntity.ok(ApiResponse.success("break-glass 이력 조회 성공",
				breakGlassService.getHistory(authentication, projectId, environment, limit, nowUtc())));
	}

	// GET /api/rollout/break-glass/{grantId}/usage (우회 호출 사용량·비용, 조직 멤버)
	@GetMapping("/{grantId}/usage")
	public ResponseEntity<ApiResponse<BreakGlassUsageResponse>> usage(
			Authentication authentication,
			@PathVariable String grantId) {
		return ResponseEntity.ok(ApiResponse.success("break-glass 사용량 조회 성공",
				breakGlassUsageService.getUsage(authentication, grantId, nowUtc())));
	}

	private LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}
}
