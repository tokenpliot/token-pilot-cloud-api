package io.tokenpilot.sample;

import io.tokenpilot.core.RoundingPolicy;
import io.tokenpilot.core.domain.Cost;

final class CostBoundaryFormatter {

    private CostBoundaryFormatter() {
    }

    static String format(Cost cost) {
        return RoundingPolicy.COST_BOUNDARY_ROUNDING.apply(cost)
                                                   .value()
                                                   .toPlainString();
    }
}
