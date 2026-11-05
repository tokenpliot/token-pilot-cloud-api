package com.tokenledgercloud.api.domain.shadow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import com.tokenledgercloud.api.domain.decision.DecisionOutcome;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;

/** Historical actual-cost replay only. This is not the pre-call upper-bound evaluator owned by #15. */
public final class ShadowEvaluator {
    public static final String VERSION = "shadow-actual-ledger-v1";
    private ShadowEvaluator() {}

    public static Evaluation evaluate(Sample sample, List<PolicyBinding> policies) {
        List<Finding> findings = new ArrayList<>();
        var active = policies.stream().filter(p -> p.rules().active()).toList();
        if (policies.isEmpty()) return unknown("POLICY_NOT_CONFIGURED");
        if (active.isEmpty()) return new Evaluation(DecisionOutcome.ALLOW, List.of());
        if (sample.costUsd() == null || !"SUCCEEDED".equals(sample.callStatus())) {
            return unknown(sample.costUsd() == null ? sample.costStatus() : "PRECALL_COST_NOT_OBSERVED");
        }
        boolean denied = false;
        boolean incomplete = false;
        for (var policy : active) {
            var rules = policy.rules();
            boolean projectScope = policy.environment() == null;
            BigDecimal before = projectScope ? sample.projectSpendBeforeUsd() : sample.environmentSpendBeforeUsd();
            boolean complete = projectScope ? sample.projectSpendComplete() : sample.environmentSpendComplete();
            BigDecimal projected = before.add(sample.costUsd());
            denied |= exceeds(findings, "MONTHLY_HARD_LIMIT", policy.version(), rules.monthlyHardLimitUsd(), projected);
            denied |= exceeds(findings, "REQUEST_HARD_LIMIT", policy.version(), rules.requestHardLimitUsd(), sample.costUsd());
            exceeds(findings, "MONTHLY_SOFT_LIMIT", policy.version(), rules.monthlySoftLimitUsd(), projected);
            exceeds(findings, "REQUEST_SOFT_LIMIT", policy.version(), rules.requestSoftLimitUsd(), sample.costUsd());
            if (!complete) {
                incomplete = true;
                findings.add(new Finding("MONTHLY_SPEND_INCOMPLETE", policy.version(), null, before));
            }
        }
        // Known spend already exceeding a hard limit is a denial even when additional spend is unknown.
        return new Evaluation(denied ? DecisionOutcome.DENY_BUDGET
            : incomplete ? DecisionOutcome.INDETERMINATE : DecisionOutcome.ALLOW, List.copyOf(findings));
    }

    private static Evaluation unknown(String code) {
        return new Evaluation(DecisionOutcome.INDETERMINATE, List.of(new Finding(code, null, null, null)));
    }

    private static boolean exceeds(List<Finding> findings, String code, String version, BigDecimal limit, BigDecimal observed) {
        if (limit == null || observed.compareTo(limit) <= 0) return false;
        findings.add(new Finding(code, version, limit, observed));
        return true;
    }

    public static Summary summarize(List<Decision> decisions) {
        int be = 0, ae = 0, bd = 0, ad = 0, changed = 0, newlyDenied = 0, candidates = 0;
        for (var d : decisions) {
            if (d.before().outcome() != DecisionOutcome.INDETERMINATE) be++;
            if (d.after().outcome() != DecisionOutcome.INDETERMINATE) ae++;
            if (d.before().outcome().isPolicyDenial()) bd++;
            if (d.after().outcome().isPolicyDenial()) ad++;
            if (d.changed()) changed++;
            if (!d.before().outcome().isPolicyDenial() && d.after().outcome().isPolicyDenial()) newlyDenied++;
            if (d.falseDenialCandidate()) candidates++;
        }
        return new Summary(decisions.size(), be, ae, bd, ad, decisions.size() - ae, changed, newlyDenied,
            candidates, rate(bd, be), rate(ad, ae));
    }

    private static BigDecimal rate(int denied, int evaluated) {
        return evaluated == 0 ? null : BigDecimal.valueOf(denied).divide(BigDecimal.valueOf(evaluated), 6, RoundingMode.HALF_EVEN);
    }
}
