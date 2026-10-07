package com.tokenledgercloud.api.domain.ingestion.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;

/**
 * Canonical SHA-256 fingerprint of an ingestion payload (#10).
 *
 * <p>Idempotency key: {@code (project, environment, eventId ?? requestId)}. The key fields,
 * the authenticated scope (organization, project, api key) are NOT part of the fingerprint;
 * everything the client reports about the call is.
 *
 * <p>Included, in this fixed order: provider, model, prompt/completion/reasoning/cachedPrompt tokens,
 * totalTokens, prompt/completion/reasoning/cachedPrompt/total cost, pricingPlanId, pricingVersion,
 * sourceType, metadata (after forbidden keys were dropped), occurredAt.
 *
 * <p>Normalization, so semantically equal payloads hash equally:
 * <ul>
 * <li>null optional tokens/costs are 0; null totals are the sum of their parts (same as storage)</li>
 * <li>BigDecimal compared by value ({@code 0.10} == {@code 0.1}), rendered plain</li>
 * <li>blank sourceType is {@code sdk}</li>
 * <li>metadata object keys sorted recursively; null/blank/empty metadata are identical</li>
 * <li>occurredAt is UTC, truncated to microseconds (storage precision)</li>
 * </ul>
 * Changing any rule changes every hash, so bump {@link #VERSION}.
 */
public final class PayloadFingerprint {

	static final String VERSION = "v1";

	private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

	private PayloadFingerprint() {
	}

	public static String of(UsageLogCreateRequest request) {
		long prompt = zero(request.promptTokens());
		long completion = zero(request.completionTokens());
		long reasoning = zero(request.reasoningTokens());
		long cached = zero(request.cachedPromptTokens());
		long totalTokens = request.resolvedTotalTokens();

		BigDecimal promptCost = zero(request.promptCostUsd());
		BigDecimal completionCost = zero(request.completionCostUsd());
		BigDecimal reasoningCost = zero(request.reasoningCostUsd());
		BigDecimal cachedCost = zero(request.cachedPromptCostUsd());
		BigDecimal totalCost = request.resolvedTotalCostUsd();

		StringBuilder canonical = new StringBuilder(VERSION).append('\n');
		field(canonical, "provider", request.provider());
		field(canonical, "model", request.model());
		field(canonical, "promptTokens", prompt);
		field(canonical, "completionTokens", completion);
		field(canonical, "reasoningTokens", reasoning);
		field(canonical, "cachedPromptTokens", cached);
		field(canonical, "totalTokens", totalTokens);
		field(canonical, "promptCostUsd", plain(promptCost));
		field(canonical, "completionCostUsd", plain(completionCost));
		field(canonical, "reasoningCostUsd", plain(reasoningCost));
		field(canonical, "cachedPromptCostUsd", plain(cachedCost));
		field(canonical, "totalCostUsd", plain(totalCost));
		field(canonical, "pricingPlanId", request.pricingPlanId());
		field(canonical, "pricingVersion", request.pricingVersion());
		field(canonical, "sourceType", sourceType(request.sourceType()));
		field(canonical, "metadata", canonicalMetadata(request.metadataJson()));
		field(canonical, "occurredAt", occurredAt(request.occurredAt()));

		return sha256Hex(canonical.toString());
	}

	/** Length-prefixed so values containing separators cannot collide across fields. */
	private static void field(StringBuilder out, String name, Object value) {
		String text = value == null ? "" : value.toString();
		out.append(name).append(':').append(value == null ? '-' : '+').append(text.length()).append(':')
			.append(text).append('\n');
	}

	private static String canonicalMetadata(String metadataJson) {
		if (metadataJson == null || metadataJson.isBlank()) {
			return "";
		}
		try {
			Object tree = CANONICAL_MAPPER.readValue(metadataJson, Object.class);
			if (tree instanceof java.util.Map<?, ?> map && map.isEmpty()) {
				return "";
			}
			return CANONICAL_MAPPER.writeValueAsString(tree);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("metadata must be valid JSON.", exception);
		}
	}

	private static String occurredAt(LocalDateTime occurredAt) {
		return occurredAt == null ? null : occurredAt.truncatedTo(ChronoUnit.MICROS).toString();
	}

	private static String sourceType(String sourceType) {
		return sourceType == null || sourceType.isBlank() ? "sdk" : sourceType;
	}

	private static String plain(BigDecimal value) {
		return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
	}

	private static long zero(Long value) {
		return value == null ? 0L : value;
	}

	private static BigDecimal zero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}
}
