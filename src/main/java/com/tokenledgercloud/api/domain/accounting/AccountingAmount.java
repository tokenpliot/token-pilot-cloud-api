package com.tokenledgercloud.api.domain.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Phase 1 accounting contract; intentionally separate from legacy USD projections. */
public record AccountingAmount(Kind kind, PricingStatus pricingStatus, BigDecimal amount,
                               String currency, Source source) {
    public enum Kind { ESTIMATED, ACTUAL }
    public enum PricingStatus { PRICED, UNPRICED }
    public enum Source { SERVER_CATALOG, PROVIDER_BILLING, NONE }

    public AccountingAmount {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(pricingStatus, "pricingStatus");
        Objects.requireNonNull(source, "source");
        if (!"USD".equals(currency)) {
            throw new IllegalArgumentException("Phase 1 accounting supports USD only");
        }
        if (pricingStatus == PricingStatus.UNPRICED) {
            if (amount != null || source != Source.NONE) {
                throw new IllegalArgumentException("Unpriced amounts must be null with source NONE");
            }
        } else {
            Objects.requireNonNull(amount, "priced amount");
            if (amount.signum() < 0 || source == Source.NONE) {
                throw new IllegalArgumentException("Priced amounts require a nonnegative amount and an authoritative source");
            }
            // Calculation rounds once before constructing this value; external input is never silently rounded.
            amount = amount.setScale(6, RoundingMode.UNNECESSARY);
            if (amount.precision() > 18) {
                throw new IllegalArgumentException("Amount exceeds DECIMAL(18,6)");
            }
        }
        if (source == Source.PROVIDER_BILLING && kind != Kind.ACTUAL) {
            throw new IllegalArgumentException("Provider billing cannot be an estimate");
        }
    }
}
