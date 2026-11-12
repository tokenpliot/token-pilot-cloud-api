package com.tokenledgercloud.api.domain.shadow.dto;

import java.math.BigDecimal;
import java.util.List;
import com.tokenledgercloud.api.domain.decision.*;
import com.tokenledgercloud.api.domain.policy.PolicyRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ShadowModels {
    private ShadowModels() {}
    public record RunRequest(@NotBlank @Size(max = 36) String baselineSnapshotId,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String month,
        @NotNull @Valid PolicyRules candidate, @NotBlank @Size(max = 500) String reason) {}
    public record PolicyBinding(String policyId, String snapshotId, String version, String environment, PolicyRules rules) {}
    public record Finding(String code, String message) {}
    public record Evaluation(DecisionOutcome outcome, List<Finding> findings, String requiredCostUsd,
        String remainingMonthlyBudgetUsd, String retryAt) {}
    public record Sample(String decisionId, String requestId, String environment, String evaluatedAt,
        String algorithmVersion, String pricingSnapshotId, String pricingVersion, String pricingChecksum,
        String evaluationInputJson, List<PolicyBinding> policies) {}
    public record Decision(Sample sample, Evaluation before, Evaluation after, boolean changed,
        boolean falseDenialCandidate, String replayStatus) {}
    public record Summary(int sampled, int baselineEvaluated, int candidateEvaluated,
        int baselineDenied, int candidateDenied, int indeterminate, int changed, int newlyDenied,
        int falseDenialCandidates, BigDecimal baselineDenialRate, BigDecimal candidateDenialRate) {}
    public record StoredRun(String id, String projectId, String environment, String month,
        String algorithmVersion, String actorId, String createdAt, String reason,
        PolicyBinding baselinePolicy, PolicyBinding candidatePolicy, List<Decision> decisions) {}
    public record DecisionPage(String runId, int matching, Integer nextOffset, List<Decision> items) {}
    public enum ActualExecution { PROCEEDED, BLOCKED, FAILED, UNKNOWN }
    public enum CustomerOverride { NONE, ALLOW, BLOCK }
    public record ExecutionCommand(String schemaVersion, String requestId, String environment, String decisionId,
        DecisionOutcome reportedDecision, ActualExecution actualExecution, CustomerOverride customerOverride,
        EnforcementMode customerMode, String occurredAt, String reasonCode) {}
    public record ExecutionView(String id, String decisionId, String requestId, String environment,
        DecisionOutcome serverOutcome, DecisionOutcome reportedDecision, ActualExecution actualExecution,
        CustomerOverride customerOverride, EnforcementMode customerMode, String occurredAt, String recordedAt,
        List<String> categories, String evidenceSource, String actorId, String reasonCode) {}
    public record RolloutRequest(@PositiveOrZero long expectedVersion,
        @Size(min = 1, max = 20) String environment, @NotNull @Valid EnforcementSettings settings,
        boolean optIn, @Size(max = 36) String evidenceRunId, @NotBlank @Size(max = 500) String reason) {}
    public record Rollout(String id, long revision, String projectId, String environment,
        EnforcementSettings before, Boolean beforeOptIn, EnforcementSettings after, boolean afterOptIn,
        String evidenceRunId, String actorId, String recordedAt, String reason, String evidenceSource) {}
    public record PilotReport(String runId, String projectId, String environment, String month,
        String algorithmVersion, String costBasis, String createdAt, String actorId, String reason,
        PolicyBinding baselinePolicy, PolicyBinding candidatePolicy, Summary summary,
        int customerFeedbackEvents, int serverUnavailableEvents, int customerOverrideEvents,
        String rolloutReadiness, List<String> limitations) {}
}
