package com.tokenledgercloud.api.domain.shadow.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.tokenledgercloud.api.domain.decision.DecisionOutcome;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;
import com.tokenledgercloud.api.domain.shadow.entity.DecisionExecutionEvent;
import com.tokenledgercloud.api.domain.shadow.repository.*;
import com.tokenledgercloud.api.global.exception.*;
import com.tokenledgercloud.api.global.util.HashingUtil;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;

@Service @RequiredArgsConstructor
public class DecisionExecutionService {
    private final ProjectApiKeyAuthenticator authenticator;
    private final ProjectRepository projects;
    private final EntityManager entityManager;
    private final ShadowDecisionRepository decisions;
    private final DecisionExecutionRepository events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionView record(String rawKey, String idempotencyKey, ExecutionCommand request) {
        validate(request);
        String keyHash = ShadowService.keyHash(idempotencyKey);
        var key = authenticator.authenticate(rawKey, request.environment());
        var project = projects.lockByIdAndOrganizationId(key.projectId(), key.organizationId())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        if (project.getStatus() != ProjectStatus.ACTIVE || !project.getOrganizationId().equals(key.organizationId()))
            throw new ApiException(ErrorCode.FORBIDDEN);
        String fingerprint = HashingUtil.sha256(ShadowJson.write(request));
        var previous = events.findByProjectIdAndKeyHash(key.projectId(), keyHash);
        if (previous.isPresent()) {
            if (!fingerprint.equals(previous.get().getFingerprint())) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return ShadowJson.read(previous.get().getPayloadJson(), ExecutionView.class);
        }
        String recordId = null;
        DecisionOutcome serverOutcome = null;
        if (request.decisionId() != null) {
            recordId = recordId(request.decisionId());
            var bound = decisions.scoped(recordId, key.organizationId(), key.projectId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (!bound.getContext().getEnvironment().equals(request.environment())
                || !bound.getContext().getRequestId().equals(request.requestId())) throw new ApiException(ErrorCode.NOT_FOUND);
            serverOutcome = bound.getDecision().getOutcome();
            if (serverOutcome != request.reportedDecision()) throw invalid("reportedDecision does not match the bound server decision.");
        } else if (request.reportedDecision() != DecisionOutcome.CONTROL_PLANE_UNAVAILABLE
            && request.reportedDecision() != DecisionOutcome.INDETERMINATE) {
            throw invalid("A policy outcome requires a server decisionId.");
        }
        if ((request.customerOverride() == CustomerOverride.ALLOW && request.actualExecution() != ActualExecution.PROCEEDED)
            || (request.customerOverride() == CustomerOverride.BLOCK && request.actualExecution() != ActualExecution.BLOCKED))
            throw invalid("Override and actual execution disagree.");
        List<String> categories = new ArrayList<>();
        if (request.reportedDecision().isPolicyDenial()) categories.add("POLICY_DENIAL");
        if (request.reportedDecision() == DecisionOutcome.CONTROL_PLANE_UNAVAILABLE) categories.add("SERVER_UNAVAILABLE");
        if (request.customerOverride() != CustomerOverride.NONE) categories.add("CUSTOMER_OVERRIDE");
        if (request.reportedDecision() == DecisionOutcome.INDETERMINATE) categories.add("INDETERMINATE");
        Instant occurred = Instant.parse(request.occurredAt()).truncatedTo(ChronoUnit.MICROS);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ExecutionView result = new ExecutionView(UUID.randomUUID().toString(), request.decisionId(), request.requestId(),
            request.environment(), serverOutcome, request.reportedDecision(), request.actualExecution(),
            request.customerOverride(), request.customerMode(), occurred.toString(), now.toString(), List.copyOf(categories),
            "CUSTOMER_REPORTED_PROJECT_KEY", key.apiKeyId(), request.reasonCode());
        events.save(DecisionExecutionEvent.create(result, key.organizationId(), key.projectId(), recordId, keyHash, fingerprint,
            LocalDateTime.ofInstant(occurred, ZoneOffset.UTC), LocalDateTime.ofInstant(now, ZoneOffset.UTC), ShadowJson.write(result)));
        return result;
    }
    public static String recordId(String decisionId) {
        if (decisionId == null || !decisionId.matches("dec-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid("Invalid decisionId.");
        return decisionId.substring(4);
    }
    private static void validate(ExecutionCommand r) {
        if (r == null || !"2026-10-01".equals(r.schemaVersion()) || r.requestId() == null
            || !r.requestId().matches("[A-Za-z0-9._:-]{1,128}") || r.environment() == null
            || !r.environment().matches("[A-Za-z0-9._:-]{1,20}") || r.reportedDecision() == null
            || r.actualExecution() == null || r.customerOverride() == null || r.customerMode() == null
            || r.reasonCode() == null || !r.reasonCode().matches("[A-Z0-9_]{1,64}")) throw invalid("Invalid execution event.");
        try {
            Instant at = Instant.parse(r.occurredAt());
            if (at.isAfter(Instant.now().plusSeconds(300)) || at.isBefore(Instant.parse("2000-01-01T00:00:00Z")))
                throw invalid("Invalid event time.");
        } catch (DateTimeException | NullPointerException e) { throw invalid("occurredAt must be a UTC ISO instant."); }
    }
    private static ApiException invalid(String message) { return new ApiException(ErrorCode.INVALID_INPUT, message); }
}
