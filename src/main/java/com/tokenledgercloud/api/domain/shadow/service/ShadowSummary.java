package com.tokenledgercloud.api.domain.shadow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import com.tokenledgercloud.api.domain.decision.DecisionOutcome;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.*;

/** Denial rates use determinate decisions as the denominator; missing evidence is never zero cost. */
public final class ShadowSummary {
    private ShadowSummary() {}

    public static Summary summarize(List<Decision> decisions) {
        int be = 0, ae = 0, bd = 0, ad = 0, changed = 0, newlyDenied = 0, candidates = 0;
        for (var d : decisions) {
            if ((d.before().outcome() == DecisionOutcome.ALLOW || d.before().outcome().isPolicyDenial())) be++;
            if ((d.after().outcome() == DecisionOutcome.ALLOW || d.after().outcome().isPolicyDenial())) ae++;
            if (d.before().outcome().isPolicyDenial()) bd++;
            if (d.after().outcome().isPolicyDenial()) ad++;
            if (d.changed()) changed++;
            if (d.before().outcome() == DecisionOutcome.ALLOW && d.after().outcome().isPolicyDenial()) newlyDenied++;
            if (d.falseDenialCandidate()) candidates++;
        }
        return new Summary(decisions.size(), be, ae, bd, ad, decisions.size() - ae, changed, newlyDenied,
            candidates, rate(bd, be), rate(ad, ae));
    }

    private static BigDecimal rate(int denied, int evaluated) {
        return evaluated == 0 ? null : BigDecimal.valueOf(denied).divide(BigDecimal.valueOf(evaluated), 6, RoundingMode.HALF_EVEN);
    }
}
