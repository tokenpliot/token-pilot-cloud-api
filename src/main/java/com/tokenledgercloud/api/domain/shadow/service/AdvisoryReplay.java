package com.tokenledgercloud.api.domain.shadow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.accounting.ServerPriceCalculator;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.decision.*;
import com.tokenledgercloud.api.domain.decision.AdvisoryEvaluator.*;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels;
import com.tokenledgercloud.api.domain.shadow.dto.ShadowModels.PolicyBinding;

/** Adapter for #15's persisted advisory-v1 inputs. Never consults current prices, policies or balances. */
public final class AdvisoryReplay {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String VERSION = "shadow-advisory-v1";
    private AdvisoryReplay() {}

    public record Result(ShadowModels.Evaluation before, ShadowModels.Evaluation after, String status) {}

    public static Result replay(DecisionRecord record, PricingSnapshot price, List<PolicyBinding> policies, PolicyBinding candidate) {
        DecisionResponse response = ShadowJson.read(record.getResponseJson(), DecisionResponse.class);
        var before = new ShadowModels.Evaluation(response.outcome(),
            response.reasons().stream().map(r -> new ShadowModels.Finding(r.code(), r.message())).toList(),
            response.requiredCostUsd(), response.remainingMonthlyBudgetUsd(), response.retryAt());
        if (!AdvisoryEvaluator.ALGORITHM_VERSION.equals(record.getAlgorithmVersion())) return unsupported(before, "UNSUPPORTED_ALGORITHM");
        try {
            JsonNode saved = JSON.readTree(record.getEvaluationInputJson());
            if (!AdvisoryEvaluator.ALGORITHM_VERSION.equals(saved.path("algorithmVersion").asText())) return unsupported(before, "UNSUPPORTED_ALGORITHM");
            if (!saved.path("estimatedInputTokens").canConvertToLong() || !saved.path("maxOutputTokens").canConvertToLong()
                || !saved.path("scopes").isArray()) return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
            long inputTokens = saved.path("estimatedInputTokens").longValue();
            long outputTokens = saved.path("maxOutputTokens").longValue();
            Instant evaluatedAt = Instant.parse(saved.path("evaluatedAt").asText());
            PriceInput priceInput = priceInput(price, saved);
            List<ScopeInput> original = new ArrayList<>();
            List<ScopeInput> proposed = new ArrayList<>();
            Set<Scope> seen = new HashSet<>();
            boolean activatedWithoutBalance = false;
            for (var policy : policies) {
                Scope scope = policy.environment() == null ? Scope.PROJECT : Scope.ENVIRONMENT;
                JsonNode balance = null;
                for (var node : saved.path("scopes")) if (scope.name().equals(node.path("scope").asText())) {
                    if (balance != null) return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
                    balance = node;
                }
                if (balance == null || !policy.version().equals(balance.path("policyVersion").asText())
                    || balance.path("active").asBoolean() != policy.rules().active()) return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
                BigDecimal spend = new BigDecimal(balance.path("monthSpendUsd").asText());
                original.add(new ScopeInput(scope, policy.rules(), policy.version(), spend));
                boolean replaced = policy.policyId().equals(candidate.policyId());
                if (replaced && !policy.rules().active() && candidate.rules().active()) activatedWithoutBalance = true;
                proposed.add(new ScopeInput(scope, replaced ? candidate.rules() : policy.rules(),
                    replaced ? candidate.version() : policy.version(), spend));
                if (!seen.add(scope)) return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
            }
            if (seen.size() != saved.path("scopes").size()) return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
            var replayed = map(AdvisoryEvaluator.evaluate(new Input(inputTokens, outputTokens, priceInput, original, evaluatedAt)));
            // Detect incompatible records/semantics instead of silently reporting a different historical baseline.
            if (!before.equals(replayed)) return unsupported(before, "BASELINE_REPLAY_MISMATCH");
            // #15 intentionally stores zero without reading spend for inactive policies. That zero is not evidence.
            if (activatedWithoutBalance) return unsupported(before, "INACTIVE_SCOPE_SPEND_NOT_CAPTURED");
            return new Result(before, map(AdvisoryEvaluator.evaluate(new Input(inputTokens, outputTokens, priceInput, proposed, evaluatedAt))), "REPRODUCED");
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException
            | com.fasterxml.jackson.core.JsonProcessingException e) {
            return unsupported(before, "REPLAY_INPUT_INCOMPLETE");
        }
    }

    private static PriceInput priceInput(PricingSnapshot price, JsonNode saved) throws com.fasterxml.jackson.core.JsonProcessingException {
        String priceId = saved.path("pricingSnapshotId").isNull() ? null : saved.path("pricingSnapshotId").asText(null);
        if (price == null) {
            if (priceId != null) throw new IllegalArgumentException("Missing bound price");
            return PriceInput.unavailable(saved.path("priceUnavailableCode").asText("PRICE_NOT_BOUND"));
        }
        if (!price.getId().equals(priceId)) throw new IllegalArgumentException("Different bound price");
        JsonNode data = JSON.readTree(price.getSnapshotJson());
        if (data.isTextual()) data = JSON.readTree(data.asText());
        JsonNode rates = data.path("rates");
        return PriceInput.available(new ServerPriceCalculator.Rates(rate(rates, "prompt"), rate(rates, "completion"),
            rate(rates, "cachedPrompt"), rate(rates, "reasoning")), data.path("rateUnitTokens").asLong(0));
    }
    private static BigDecimal rate(JsonNode rates, String key) {
        var value = rates.path(key);
        return value.isNull() || value.isMissingNode() ? null : new BigDecimal(value.asText());
    }
    private static Result unsupported(ShadowModels.Evaluation before, String reason) {
        return new Result(before, new ShadowModels.Evaluation(DecisionOutcome.INDETERMINATE,
            List.of(new ShadowModels.Finding(reason, "Stored evidence cannot support this counterfactual.")), null, null, null), reason);
    }
    private static ShadowModels.Evaluation map(AdvisoryEvaluator.Evaluation e) {
        return new ShadowModels.Evaluation(e.outcome(), e.reasons().stream().map(r -> new ShadowModels.Finding(r.code(), r.message())).toList(),
            usd(e.requiredCostUsd()), usd(e.remainingMonthlyBudgetUsd()), e.retryAt() == null ? null : e.retryAt().toString());
    }
    private static String usd(BigDecimal value) { return value == null ? null : value.setScale(6).toPlainString(); }
}
