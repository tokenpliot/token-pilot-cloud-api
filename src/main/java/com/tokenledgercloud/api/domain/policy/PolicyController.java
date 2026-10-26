package com.tokenledgercloud.api.domain.policy;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/policies/projects/{projectId}")
@RequiredArgsConstructor
public class PolicyController {
	private final PolicyService policies;
	private final PolicyContextService contexts;

	@PutMapping
	public ApiResponse<PolicyVersionView> replace(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment, @Valid @RequestBody PolicyRequests.Replace request) {
		return ApiResponse.success(policies.replace(auth, projectId, environment, request));
	}

	@PostMapping("/import-monthly-budget")
	public ApiResponse<PolicyVersionView> importBudget(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment, @Valid @RequestBody PolicyRequests.ImportBudget request) {
		return ApiResponse.success(policies.importBudget(auth, projectId, environment, request));
	}

	@PostMapping("/rollback")
	public ApiResponse<PolicyVersionView> rollback(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment, @Valid @RequestBody PolicyRequests.Rollback request) {
		return ApiResponse.success(policies.rollback(auth, projectId, environment, request));
	}

	@PostMapping("/impact")
	public ApiResponse<PolicyService.Impact> impact(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment, @Valid @RequestBody PolicyRequests.Replace request) {
		return ApiResponse.success(policies.preview(auth, projectId, environment, request));
	}

	@GetMapping
	public ApiResponse<PolicyVersionView> current(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment) {
		return ApiResponse.success(policies.current(auth, projectId, environment));
	}

	@GetMapping("/versions")
	public ApiResponse<List<PolicyVersionView>> history(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment,
		@RequestParam(defaultValue = "9223372036854775807") long beforeVersion, @RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.success(policies.history(auth, projectId, environment, beforeVersion, limit));
	}

	@GetMapping("/versions/{revision}")
	public ApiResponse<PolicyVersionView> version(Authentication auth, @PathVariable String projectId,
		@RequestParam(required = false) String environment, @PathVariable long revision) {
		return ApiResponse.success(policies.version(auth, projectId, environment, revision));
	}

	@GetMapping("/request-context")
	public ApiResponse<PolicyContextService.ContextView> explain(Authentication auth, @PathVariable String projectId,
		@RequestParam String environment, @RequestParam String requestId) {
		return ApiResponse.success(contexts.explain(auth, projectId, environment, requestId));
	}

	// Keep this validation mapping local; other API error-contract changes belong to their own issues.
	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
		org.springframework.web.bind.MissingServletRequestParameterException.class})
	public ResponseEntity<ApiResponse<Void>> malformedInput(Exception ignored) {
		return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), "Invalid policy request."));
	}
}
