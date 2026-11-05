package com.tokenledgercloud.api.domain.shadow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.decision.*;
import com.tokenledgercloud.api.domain.policy.*;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;
import com.tokenledgercloud.api.domain.shadow.entity.ShadowAudit;
import com.tokenledgercloud.api.domain.shadow.entity.ShadowAudit.Kind;
import com.tokenledgercloud.api.domain.shadow.repository.*;
import com.tokenledgercloud.api.global.exception.*;
import com.tokenledgercloud.api.global.util.HashingUtil;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ShadowService {
    static final int MAX_MONTH_EVENTS = 2000;
    private final PolicyAccess access;
    private final PolicySnapshotRepository snapshots;
    private final ShadowPolicyRepository currentPolicies;
    private final ProjectEnvironmentRepository environments;
    private final ShadowLedgerRepository ledger;
    private final ShadowAuditRepository audits;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PilotReport run(Authentication auth, String project, String key, RunRequest request) {
        var grant = access.authorize(auth, project, true);
        String org = grant.project().getOrganizationId();
        String keyHash = keyHash(key);
        String fingerprint = HashingUtil.sha256(ShadowJson.write(request));
        var duplicate = duplicate(org, project, Kind.RUN, keyHash, fingerprint);
        if (duplicate.isPresent()) return report(org, project, readRun(duplicate.get()));

        PolicySnapshot baseline = snapshots.findById(request.baselineSnapshotId())
            .filter(s -> org.equals(s.getOrganizationId()) && project.equals(s.getProjectId()) && s.getPolicyId() != null)
            .orElseThrow(ShadowService::hidden);
        scope(org, project, baseline.getEnvironment());
        YearMonth month;
        try { month = YearMonth.parse(request.month()); }
        catch (RuntimeException e) { throw invalid("month must be YYYY-MM."); }
        if (month.getYear() < 2000 || month.getYear() > 2100) throw invalid("Unsupported month.");
        if (request.candidate() == null) throw invalid("Candidate rules are required.");
        reason(request.reason());

        List<PolicyBinding> beforePolicies = new ArrayList<>(currentPolicies.current(org, project).stream()
            .filter(s -> !s.getPolicyId().equals(baseline.getPolicyId())).map(ShadowService::binding).toList());
        beforePolicies.add(binding(baseline));
        beforePolicies.sort(Comparator.comparing(p -> Objects.requireNonNullElse(p.environment(), "")));
        String runId = UUID.randomUUID().toString();
        PolicyBinding candidate = new PolicyBinding(baseline.getPolicyId(), null, "shadow:" + runId,
            baseline.getEnvironment(), request.candidate());
        List<PolicyBinding> afterPolicies = beforePolicies.stream()
            .map(p -> p.policyId().equals(candidate.policyId()) ? candidate : p).toList();

        var rows = ledger.month(org, project, month.atDay(1).atStartOfDay(),
            month.plusMonths(1).atDay(1).atStartOfDay(), PageRequest.of(0, MAX_MONTH_EVENTS + 1));
        if (rows.size() > MAX_MONTH_EVENTS) throw invalid("Pilot replay supports at most 2000 project events per month; no partial sample was saved.");
        BigDecimal projectSpend = BigDecimal.ZERO;
        Map<String, BigDecimal> environmentSpend = new HashMap<>();
        Set<String> incompleteEnvironments = new HashSet<>();
        boolean projectComplete = true;
        List<Decision> decisions = new ArrayList<>();
        for (var row : rows) {
            var u = row.getUsage();
            var v = row.getAccounting();
            var price = row.getPrice();
            BigDecimal cost = confirmedCost(row);
            String costStatus = cost == null ? "ACTUAL_COST_UNAVAILABLE" : "ACTUAL_PRICED";
            Sample sample = new Sample(u.getId(), u.getRequestId(), u.getEnvironment(), u.getOccurredAt().toString() + "Z",
                u.getCallStatus(), v == null ? null : v.getId(), v == null ? null : v.getRevision(),
                v == null ? null : v.getCostSource().name(), v == null ? null : v.getPricingSnapshotId(),
                price == null ? null : price.getVersion(), price == null ? null : price.getChecksum(), costStatus, cost,
                projectSpend, environmentSpend.getOrDefault(u.getEnvironment(), BigDecimal.ZERO),
                projectComplete, !incompleteEnvironments.contains(u.getEnvironment()));
            if (baseline.getEnvironment() == null || baseline.getEnvironment().equals(u.getEnvironment())) {
                Evaluation before = ShadowEvaluator.evaluate(sample, applicable(beforePolicies, u.getEnvironment()));
                Evaluation after = ShadowEvaluator.evaluate(sample, applicable(afterPolicies, u.getEnvironment()));
                boolean falseCandidate = before.outcome() == DecisionOutcome.ALLOW && after.outcome().isPolicyDenial()
                    && "SUCCEEDED".equals(u.getCallStatus());
                decisions.add(new Decision(sample, before, after, before.outcome() != after.outcome(), falseCandidate));
            }
            // Fixed observed traffic: a hypothetical denial never removes historical spend from later samples.
            if (cost == null) {
                projectComplete = false;
                incompleteEnvironments.add(u.getEnvironment());
            } else {
                projectSpend = projectSpend.add(cost);
                environmentSpend.merge(u.getEnvironment(), cost, BigDecimal::add);
            }
        }
        StoredRun result = new StoredRun(runId, project, baseline.getEnvironment(), month.toString(),
            ShadowEvaluator.VERSION, "HISTORICAL_ACTUAL_COST", grant.memberId(), Instant.now().toString(), request.reason(),
            List.copyOf(beforePolicies), candidate, ShadowEvaluator.summarize(decisions), List.copyOf(decisions));
        audits.save(ShadowAudit.create(runId, org, project, Kind.RUN, baseline.getEnvironment(), keyHash, fingerprint,
            null, null, grant.memberId(), ShadowJson.write(result), false, false, false));
        return report(org, project, result);
    }

    @Transactional(readOnly = true)
    public PilotReport report(Authentication auth, String project, String runId) {
        var grant = access.authorize(auth, project, false);
        String org = grant.project().getOrganizationId();
        return report(org, project, loadRun(org, project, runId));
    }

    @Transactional(readOnly = true)
    public List<PilotReport> runs(Authentication auth, String project, int page, int size) {
        var grant = access.authorize(auth, project, false);
        String org = grant.project().getOrganizationId();
        return audits.findByOrganizationIdAndProjectIdAndKindOrderByCreatedAtDescIdDesc(org, project, Kind.RUN, page(page, size))
            .stream().map(a -> report(org, project, readRun(a))).toList();
    }

    @Transactional(readOnly = true)
    public DecisionPage decisions(Authentication auth, String project, String runId, int offset, int limit,
        boolean changedOnly, boolean falseDenialOnly) {
        var grant = access.authorize(auth, project, false);
        if (offset < 0 || offset > MAX_MONTH_EVENTS || limit < 1 || limit > 200) throw invalid("Invalid pagination.");
        var run = loadRun(grant.project().getOrganizationId(), project, runId);
        List<Decision> matches = run.decisions().stream().filter(d -> !changedOnly || d.changed())
            .filter(d -> !falseDenialOnly || d.falseDenialCandidate()).toList();
        int end = Math.min(matches.size(), offset + limit);
        return new DecisionPage(runId, matches.size(), end < matches.size() ? end : null,
            matches.subList(Math.min(offset, matches.size()), end));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Feedback feedback(Authentication auth, String project, String runId, String key, FeedbackRequest request) {
        var grant = access.authorize(auth, project, true);
        String org = grant.project().getOrganizationId();
        String keyHash = keyHash(key);
        String fingerprint = HashingUtil.sha256(ShadowJson.write(List.of(runId, request)));
        var duplicate = duplicate(org, project, Kind.EXECUTION, keyHash, fingerprint);
        if (duplicate.isPresent()) return ShadowJson.read(duplicate.get().getPayloadJson(), Feedback.class);
        var run = loadRun(org, project, runId);
        Decision decision = run.decisions().stream().filter(d -> d.sample().usageEventId().equals(request.usageEventId()))
            .findFirst().orElseThrow(ShadowService::hidden);
        reason(request.reason());
        if ((request.customerOverride() == CustomerOverride.ALLOW && request.actualExecution() != ActualExecution.PROCEEDED)
            || (request.customerOverride() == CustomerOverride.BLOCK && request.actualExecution() != ActualExecution.BLOCKED)) {
            throw invalid("Override and actual execution disagree.");
        }
        boolean policyDenial = request.reportedDecision().isPolicyDenial();
        boolean unavailable = request.reportedDecision() == DecisionOutcome.CONTROL_PLANE_UNAVAILABLE;
        boolean override = request.customerOverride() != CustomerOverride.NONE;
        List<String> categories = new ArrayList<>();
        if (policyDenial) categories.add("POLICY_DENIAL");
        if (unavailable) categories.add("SERVER_UNAVAILABLE");
        if (override) categories.add("CUSTOMER_OVERRIDE");
        if (request.reportedDecision() == DecisionOutcome.INDETERMINATE) categories.add("INDETERMINATE");
        Feedback result = new Feedback(UUID.randomUUID().toString(), runId, request.usageEventId(),
            decision.sample().requestId(), decision.sample().environment(), decision.after().outcome(),
            request.actualExecution(), request.customerOverride(), request.reportedDecision(), request.customerMode(),
            List.copyOf(categories), "CUSTOMER_REPORTED_BY_MEMBER", grant.memberId(), Instant.now().toString(), request.reason());
        audits.save(ShadowAudit.create(result.id(), org, project, Kind.EXECUTION, result.environment(), keyHash, fingerprint,
            runId, null, grant.memberId(), ShadowJson.write(result), policyDenial, unavailable, override));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Feedback> feedback(Authentication auth, String project, String runId, String category, int page, int size) {
        var grant = access.authorize(auth, project, false);
        String org = grant.project().getOrganizationId();
        loadRun(org, project, runId);
        if (category != null && !List.of("POLICY_DENIAL", "SERVER_UNAVAILABLE", "CUSTOMER_OVERRIDE").contains(category)) {
            throw invalid("Unknown audit category.");
        }
        return audits.feedback(org, project, runId, category, page(page, size)).stream()
            .map(a -> ShadowJson.read(a.getPayloadJson(), Feedback.class)).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Rollout rollout(Authentication auth, String project, String key, RolloutRequest request) {
        var grant = access.authorize(auth, project, true);
        String org = grant.project().getOrganizationId();
        scope(org, project, request.environment());
        reason(request.reason());
        if (request.settings().mode() == EnforcementMode.ENFORCE && !request.optIn()) {
            throw invalid("An ENFORCE declaration requires explicit customer opt-in.");
        }
        String keyHash = keyHash(key);
        // Set iteration order is not stable across JVMs. Fingerprint an explicitly sorted representation.
        String fingerprint = HashingUtil.sha256(ShadowJson.write(Arrays.asList(request.expectedVersion(), request.environment(),
            request.settings().mode(), request.settings().enforcedOutcomes().stream().map(Enum::name).sorted().toList(),
            request.settings().onIndeterminate(), request.settings().onUnavailable(), request.optIn(), request.evidenceRunId(), request.reason())));
        var duplicate = duplicate(org, project, Kind.ROLLOUT, keyHash, fingerprint);
        if (duplicate.isPresent()) return ShadowJson.read(duplicate.get().getPayloadJson(), Rollout.class);
        Rollout previous = audits.findFirstByOrganizationIdAndProjectIdAndKindAndEnvironmentKeyOrderByRevisionDesc(
            org, project, Kind.ROLLOUT, Objects.requireNonNullElse(request.environment(), ""))
            .map(a -> ShadowJson.read(a.getPayloadJson(), Rollout.class)).orElse(null);
        long revision = previous == null ? 0 : previous.revision();
        if (request.expectedVersion() != revision) throw new ApiException(ErrorCode.CONFLICT, "Rollout audit version changed.");
        if (request.evidenceRunId() != null) {
            var evidence = loadRun(org, project, request.evidenceRunId());
            if (!Objects.equals(evidence.environment(), request.environment())) throw invalid("Report scope does not match rollout scope.");
        }
        Rollout result = new Rollout(UUID.randomUUID().toString(), revision + 1, project, request.environment(),
            previous == null ? null : previous.after(), previous == null ? null : previous.afterOptIn(),
            request.settings(), request.optIn(), request.evidenceRunId(), grant.memberId(), Instant.now().toString(),
            request.reason(), "CUSTOMER_DECLARATION_ONLY");
        audits.save(ShadowAudit.create(result.id(), org, project, Kind.ROLLOUT, request.environment(), keyHash, fingerprint,
            null, result.revision(), grant.memberId(), ShadowJson.write(result), false, false, false));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Rollout> rollouts(Authentication auth, String project, String environment, int page, int size) {
        var grant = access.authorize(auth, project, false);
        // Historical environments remain readable, like policy history.
        return audits.findByOrganizationIdAndProjectIdAndKindAndEnvironmentKeyOrderByRevisionDesc(
            grant.project().getOrganizationId(), project, Kind.ROLLOUT, Objects.requireNonNullElse(environment, ""), page(page, size))
            .stream().map(a -> ShadowJson.read(a.getPayloadJson(), Rollout.class)).toList();
    }

    private PilotReport report(String org, String project, StoredRun r) {
        return new PilotReport(r.id(), r.projectId(), r.environment(), r.month(), r.algorithmVersion(), r.costBasis(),
            r.createdAt(), r.actorId(), r.reason(), r.baselinePolicies(), r.candidatePolicy(), r.summary(),
            audits.countFeedback(org, project, r.id(), null), audits.countFeedback(org, project, r.id(), "SERVER_UNAVAILABLE"),
            audits.countFeedback(org, project, r.id(), "CUSTOMER_OVERRIDE"),
            r.summary().candidateEvaluated() == 0 ? "INSUFFICIENT_EVIDENCE" : "MANUAL_REVIEW_REQUIRED",
            List.of("Historical actual costs are not pre-call conservative upper bounds.",
                "Observed spend stays fixed after hypothetical denials; this is not a savings forecast.",
                "Missing actual costs make subsequent monthly balances incomplete.",
                "Successful calls flagged as false-denial candidates require customer review; they are not confirmed false positives.",
                "Customer feedback counts are append-only event counts, not unique calls.",
                "No live enforcement or SDK configuration is changed. Advisory runtime integration remains with #15."));
    }

    private StoredRun loadRun(String org, String project, String id) {
        return readRun(audits.findByIdAndOrganizationIdAndProjectIdAndKind(id, org, project, Kind.RUN)
            .orElseThrow(ShadowService::hidden));
    }
    private static StoredRun readRun(ShadowAudit a) { return ShadowJson.read(a.getPayloadJson(), StoredRun.class); }

    private Optional<ShadowAudit> duplicate(String org, String project, Kind kind, String keyHash, String fingerprint) {
        var existing = audits.findByOrganizationIdAndProjectIdAndKindAndKeyHash(org, project, kind, keyHash);
        if (existing.isPresent() && !existing.get().getFingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.CONFLICT, "Idempotency key was already used with different input.");
        }
        return existing;
    }

    private static String keyHash(String key) {
        if (key == null || key.length() < 8 || key.length() > 128 || !key.equals(key.strip()) || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key must be an opaque 8-128 character string.");
        }
        return HashingUtil.sha256(key);
    }

    private void scope(String org, String project, String environment) {
        if (environment != null && (environment.isBlank() || environment.length() > 20 || !environment.equals(environment.strip())
            || environments.findByProjectId(project).stream().noneMatch(e -> org.equals(e.getOrganizationId()) && environment.equals(e.getEnvironment())))) {
            throw invalid("Environment is not registered to the project.");
        }
    }
    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("A reason of 1-500 characters is required.");
    }
    private static PageRequest page(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Invalid pagination.");
        return PageRequest.of(page, size);
    }
    private static PolicyBinding binding(PolicySnapshot p) {
        return new PolicyBinding(p.getPolicyId(), p.getId(), p.getVersion(), p.getEnvironment(), p.rules());
    }
    private static List<PolicyBinding> applicable(List<PolicyBinding> all, String environment) {
        return all.stream().filter(p -> p.environment() == null || p.environment().equals(environment)).toList();
    }
    private static BigDecimal confirmedCost(ShadowLedgerRepository.Row row) {
        var u = row.getUsage();
        var v = row.getAccounting();
        if (!"VERIFIED".equals(u.getAccountingStatus()) || v == null
            || v.getPricingStatus() != AccountingAmount.PricingStatus.PRICED || !"USD".equals(v.getCurrency())
            || v.getAmount() == null || v.getAmount().signum() < 0) return null;
        if (v.getCostSource() == AccountingAmount.Source.SERVER_CATALOG) {
            var p = row.getPrice();
            if (p == null || !u.getProvider().equals(p.getProvider()) || !u.getModel().equals(p.getModel())
                || !"USD".equals(p.getCurrency())) return null;
        }
        return v.getAmount();
    }
    private static ApiException hidden() { return new ApiException(ErrorCode.NOT_FOUND); }
    private static ApiException invalid(String message) { return new ApiException(ErrorCode.INVALID_INPUT, message); }
}
