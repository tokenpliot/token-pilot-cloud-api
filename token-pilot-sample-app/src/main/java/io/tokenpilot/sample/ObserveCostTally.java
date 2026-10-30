package io.tokenpilot.sample;

import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token Pilot이 기록한 비용을 누적한다. SDK가 전송하는 것과 같은 원장 이벤트를 받으므로
 * 중앙 원장 금액과 대조하는 기준값이 된다. (예산을 끈 경로에는 tokenpilot.cost.total 지표가 없다)
 */
@Component
@Profile("observe")
public class ObserveCostTally implements LedgerListener {

    private final AtomicLong events = new AtomicLong();
    private final AtomicReference<BigDecimal> totalUsd = new AtomicReference<>(BigDecimal.ZERO);

    @Override
    public void onRecord(CostRecordedEvent event) {
        events.incrementAndGet();
        if (event.cost() != null && event.cost().value() != null
                && "USD".equals(event.cost().currency().getCurrencyCode())) {
            totalUsd.accumulateAndGet(event.cost().value(), BigDecimal::add);
        }
    }

    public long events() {
        return events.get();
    }

    public BigDecimal totalUsd() {
        return totalUsd.get();
    }
}
