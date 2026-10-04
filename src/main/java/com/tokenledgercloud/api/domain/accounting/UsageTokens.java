package com.tokenledgercloud.api.domain.accounting;

/** Cache and reasoning are subsets, not additional tokens. Null detail means unknown. */
public record UsageTokens(long promptTokens, long completionTokens,
                          Long cachedPromptTokens, Long reasoningTokens) {
    public UsageTokens {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("Token counts must be nonnegative");
        }
        validateSubset(cachedPromptTokens, promptTokens);
        validateSubset(reasoningTokens, completionTokens);
        Math.addExact(promptTokens, completionTokens);
    }

    public long totalTokens() {
        return Math.addExact(promptTokens, completionTokens);
    }

    private static void validateSubset(Long detail, long parent) {
        if (detail != null && (detail < 0 || detail > parent)) {
            throw new IllegalArgumentException("Token detail must be a subset of its parent");
        }
    }
}
