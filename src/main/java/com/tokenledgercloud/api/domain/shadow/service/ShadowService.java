package com.tokenledgercloud.api.domain.shadow.service;

import java.time.ZoneOffset;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshotRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
    private final PricingSnapshotRepository prices;
    private final ProjectEnvironmentRepository environments;
    private final ShadowDecisionRepository records;
    private final DecisionExecutionRepository executions;
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

        String runId = UUID.randomUUID().toString();
        PolicyBinding candidate = new PolicyBinding(baseline.getPolicyId(), null, "shadow:" + runId,
            baseline.getEnvironment(), request.candidate());
        var rows = records.samples(org, project, baseline.getId(), month.atDay(1).atStartOfDay(),
            month.plusMonths(1).atDay(1).atStartOfDay(), PageRequest.of(0, MAX_MONTH_EVENTS + 1));
        if (rows.size() > MAX_MONTH_EVENTS) throw invalid("Pilot replay supports at most 2000 matching decisions per month; no partial sample was saved.");
        List<Decision> decisions = new ArrayList<>();
        for (var row : rows) {
            var record = row.getDecision();
            var context = row.getContext();
            List<PolicyBinding> bound = new ArrayList<>();
            for (String id : Arrays.asList(context.getProjectPolicySnapshotId(), context.getEnvironmentPolicySnapshotId())) {
                if (id != null) bound.add(binding(snapshots.findById(id)
                    .filter(p -> org.equals(p.getOrganizationId()) && project.equals(p.getProjectId()))
                    .orElseThrow(ShadowService::hidden)));
            }
            var price = context.getPricingSnapshotId() == null ? null : prices.findById(context.getPricingSnapshotId()).orElse(null);
            Sample sample = new Sample(record.decisionId(), context.getRequestId(), context.getEnvironment(),
                record.getEvaluatedAt().toInstant(ZoneOffset.UTC).toString(), record.getAlgorithmVersion(),
                context.getPricingSnapshotId(), price == null ? null : price.getVersion(), price == null ? null : price.getChecksum(),
                record.getEvaluationInputJson(), List.copyOf(bound));
            var result = AdvisoryReplay.replay(record, price, bound, candidate);
            decisions.add(new Decision(sample, result.before(), result.after(),
                result.before().outcome() != result.after().outcome(), false, result.status()));
        }
        StoredRun result = new StoredRun(runId, project, baseline.getEnvironment(), month.toString(),
            AdvisoryReplay.VERSION, grant.memberId(), Instant.now().toString(), request.reason(),
            binding(baseline), candidate, List.copyOf(decisions));
        audits.save(ShadowAudit.create(runId, org, project, Kind.RUN, baseline.getEnvironment(), keyHash, fingerprint,
            null, grant.memberId(), ShadowJson.write(result)));
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
        List<Decision> matches = withExecution(grant.project().getOrganizationId(), project, run).stream().filter(d -> !changedOnly || d.changed())
            .filter(d -> !falseDenialOnly || d.falseDenialCandidate()).toList();
        int end = Math.min(matches.size(), offset + limit);
        return new DecisionPage(runId, matches.size(), end < matches.size() ? end : null,
            matches.subList(Math.min(offset, matches.size()), end));
    }

    @Transactional(readOnly = true)
    public List<ExecutionView> executions(Authentication auth, String project, String environment, String decisionId,
        String category, int page, int size) {
        var grant = access.authorize(auth, project, false);
        String org = grant.project().getOrganizationId();
        if (category != null && !List.of("POLICY_DENIAL", "SERVER_UNAVAILABLE", "CUSTOMER_OVERRIDE").contains(category))
            throw invalid("Unknown audit category.");
        String id = decisionId == null ? null : DecisionExecutionService.recordId(decisionId);
        if (id != null) records.scoped(id, org, project).orElseThrow(ShadowService::hidden);
        return executions.list(org, project, environment, id, category, page(page, size)).stream()
            .map(e -> ShadowJson.read(e.getPayloadJson(), ExecutionView.class)).toList();
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
            result.revision(), grant.memberId(), ShadowJson.write(result)));
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
        var summary = ShadowSummary.summarize(withExecution(org, project, r));
        var month = YearMonth.parse(r.month());
        var from = month.atDay(1).atStartOfDay();
        var to = month.plusMonths(1).atDay(1).atStartOfDay();
        var ids = recordIds(r);
        return new PilotReport(r.id(), r.projectId(), r.environment(), r.month(), r.algorithmVersion(), "SAVED_ADVISORY_UPPER_BOUND",
            r.createdAt(), r.actorId(), r.reason(), r.baselinePolicy(), r.candidatePolicy(), summary,
            executions.countForReport(org, project, ids, from, to, r.environment(), null),
            executions.countForReport(org, project, ids, from, to, r.environment(), "SERVER_UNAVAILABLE"),
            executions.countForReport(org, project, ids, from, to, r.environment(), "CUSTOMER_OVERRIDE"),
            summary.candidateEvaluated() == 0 ? "INSUFFICIENT_EVIDENCE" : "MANUAL_REVIEW_REQUIRED",
            List.of("Only saved decisions bound to the selected baseline snapshot are sampled; no full-traffic representativeness is claimed.",
                "Original balances and price snapshots stay fixed; hypothetical denials do not forecast savings or future traffic.",
                "Unsupported replay inputs and inactive-to-active changes without captured spend remain INDETERMINATE.",
                "False-denial candidates require a reported PROCEEDED execution and customer review; they are not confirmed false positives.",
                "Execution evidence is customer-reported. Counts are append-only events, may grow after the run, and are not unique calls.",
                "Unbound outage events are grouped by reported UTC month and scope, not attributed to a server decision.",
                "No live policy, enforcement or SDK configuration is changed; rollout readiness always requires manual review."));
    }

    private List<Decision> withExecution(String org, String project, StoredRun r) {
        Set<String> proceeded = executions.proceeded(org, project, recordIds(r));
        return r.decisions().stream().map(d -> new Decision(d.sample(), d.before(), d.after(), d.changed(),
            d.before().outcome() == DecisionOutcome.ALLOW && d.after().outcome().isPolicyDenial()
                && proceeded.contains(DecisionExecutionService.recordId(d.sample().decisionId())), d.replayStatus())).toList();
    }

    private static List<String> recordIds(StoredRun r) {
        // Avoid an empty SQL IN clause on both H2 and MySQL.
        return r.decisions().isEmpty() ? List.of("00000000-0000-0000-0000-000000000000") : r.decisions().stream()
            .map(d -> DecisionExecutionService.recordId(d.sample().decisionId())).toList();
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

    static String keyHash(String key) {
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
    private static ApiException hidden() { return new ApiException(ErrorCode.NOT_FOUND); }
    private static ApiException invalid(String message) { return new ApiException(ErrorCode.INVALID_INPUT, message); }
}
