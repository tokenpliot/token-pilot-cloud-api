package com.tokenledgercloud.api.domain.shadow.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;
import com.tokenledgercloud.api.domain.shadow.service.ShadowService;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import com.tokenledgercloud.api.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "token-pilot.shadow.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/shadow/projects/{projectId}")
@RequiredArgsConstructor
public class ShadowController {
    private final ShadowService shadow;

    @PostMapping("/runs")
    public ApiResponse<PilotReport> run(Authentication auth, @PathVariable String projectId,
        @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RunRequest request) {
        return ApiResponse.success(shadow.run(auth, projectId, key, request));
    }

    @GetMapping("/runs")
    public ApiResponse<List<PilotReport>> runs(Authentication auth, @PathVariable String projectId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(shadow.runs(auth, projectId, page, size));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<PilotReport> report(Authentication auth, @PathVariable String projectId, @PathVariable String runId) {
        return ApiResponse.success(shadow.report(auth, projectId, runId));
    }

    @GetMapping("/runs/{runId}/decisions")
    public ApiResponse<DecisionPage> decisions(Authentication auth, @PathVariable String projectId, @PathVariable String runId,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "false") boolean changedOnly, @RequestParam(defaultValue = "false") boolean falseDenialOnly) {
        return ApiResponse.success(shadow.decisions(auth, projectId, runId, offset, limit, changedOnly, falseDenialOnly));
    }

    @GetMapping("/execution-events")
    public ApiResponse<List<ExecutionView>> executions(Authentication auth, @PathVariable String projectId,
        @RequestParam(required = false) String environment, @RequestParam(required = false) String decisionId,
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(shadow.executions(auth, projectId, environment, decisionId, category, page, size));
    }

    @PostMapping("/rollout-events")
    public ApiResponse<Rollout> rollout(Authentication auth, @PathVariable String projectId,
        @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RolloutRequest request) {
        return ApiResponse.success(shadow.rollout(auth, projectId, key, request));
    }

    @GetMapping("/rollout-events")
    public ApiResponse<List<Rollout>> rollouts(Authentication auth, @PathVariable String projectId,
        @RequestParam(required = false) String environment,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(shadow.rollouts(auth, projectId, environment, page, size));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), "Invalid shadow request."));
    }
}
