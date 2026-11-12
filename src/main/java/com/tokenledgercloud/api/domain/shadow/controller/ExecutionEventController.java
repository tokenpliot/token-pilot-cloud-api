package com.tokenledgercloud.api.domain.shadow.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import com.tokenledgercloud.api.domain.shadow.dto.ExecutionEventRequest;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.ExecutionView;
import com.tokenledgercloud.api.domain.shadow.service.DecisionExecutionService;
import com.tokenledgercloud.api.global.exception.*;
import com.tokenledgercloud.api.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(prefix = "token-pilot.shadow", name = "enabled", havingValue = "true")
@RequestMapping("/api/control/v1/execution-events")
public class ExecutionEventController {
    private final DecisionExecutionService service;
    @PostMapping
    public ApiResponse<ExecutionView> record(@RequestHeader(name = "X-API-Key", required = false) String apiKey,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody ExecutionEventRequest request) {
        if (apiKey == null || apiKey.isBlank()) throw new ApiException(ErrorCode.UNAUTHORIZED);
        if (request.hasUnknownFields()) throw new ApiException(ErrorCode.INVALID_INPUT, "Undefined fields are not accepted.");
        return ApiResponse.success(service.record(apiKey, idempotencyKey, request.command()));
    }
}
