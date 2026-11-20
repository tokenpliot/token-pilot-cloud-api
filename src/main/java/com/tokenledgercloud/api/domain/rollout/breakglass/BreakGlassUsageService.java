package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.AccountingStatus;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.policy.PolicyAccess;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassUsageResponse;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * break-glass로 우회한 호출의 사용량·비용 조회. 읽기 전용이다. (이슈 #19)
 * - 우회 호출은 SDK가 사용량 이벤트 metadata에 {@value BreakGlassGrant#USAGE_METADATA_KEY}로 grant id를 붙여 보낸다.
 *   키 형식은 ingestion 규칙(소문자·숫자·_.-)을 따라야 strict 모드에서도 거부되지 않는다.
 * - 비용은 #11 원장 규칙을 따른다: 최신 ACTUAL 개정본이 PRICED인 금액만 더하고, 격리(QUARANTINED) 이벤트는 뺀다.
 * - 같은 grant id가 붙었지만 유효 기간 밖에 일어난 호출은 따로 센다. SDK가 만료·취소를 늦게 반영했는지 확인하는 용도다.
 * - 조회 권한은 감사 이력과 같다: 조직 멤버면 역할과 관계없이 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
public class BreakGlassUsageService {

	private final BreakGlassGrantRepository grantRepository;
	private final BreakGlassUsageRepository usageRepository;
	private final PolicyAccess policyAccess;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Transactional(readOnly = true)
	public BreakGlassUsageResponse getUsage(Authentication authentication, String grantId, LocalDateTime now) {
		BreakGlassGrant grant = grantRepository.findById(grantId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "break-glass grant를 찾을 수 없습니다."));
		// 다른 조직의 grant는 존재 여부도 드러내지 않도록 PolicyAccess가 NOT_FOUND로 응답한다.
		policyAccess.authorize(authentication, grant.getProjectId(), false);

		// grant id는 서버가 만든 UUID라서 LIKE 와일드카드(%, _)가 들어 있지 않다.
		List<UsageLog> events = usageRepository.findCandidates(grant.getOrganizationId(), grant.getProjectId(),
						grant.getEnvironment(), "%" + grant.getId() + "%").stream()
				.filter(event -> grant.getId().equals(taggedGrantId(event.getMetadataJson())))
				.toList();

		Map<String, UsageAccountingValue> actualValues = events.isEmpty()
				? Map.of()
				: usageRepository.findLatestValues(events.stream().map(UsageLog::getId).toList(),
								AccountingAmount.Kind.ACTUAL).stream()
						.collect(Collectors.toMap(UsageAccountingValue::getUsageEventId, Function.identity()));

		LocalDateTime windowStart = grant.getIssuedAt();
		LocalDateTime windowEnd = grant.getRevokedAt() != null ? grant.getRevokedAt() : grant.getExpiresAt();
		Map<Boolean, List<UsageLog>> byWindow = events.stream().collect(Collectors.partitioningBy(event ->
				!event.getOccurredAt().isBefore(windowStart) && event.getOccurredAt().isBefore(windowEnd)));

		return new BreakGlassUsageResponse(
				grant.getId(),
				grant.getProjectId(),
				grant.getEnvironment(),
				grant.statusAt(now),
				windowStart.atOffset(ZoneOffset.UTC),
				windowEnd.atOffset(ZoneOffset.UTC),
				summarize(byWindow.get(true), actualValues),
				summarize(byWindow.get(false), actualValues),
				now.atOffset(ZoneOffset.UTC));
	}

	private BreakGlassUsageResponse.Summary summarize(List<UsageLog> events, Map<String, UsageAccountingValue> actualValues) {
		long totalTokens = 0;
		BigDecimal actualCost = BigDecimal.ZERO;
		long unpriced = 0;
		long legacy = 0;
		BigDecimal legacyCost = BigDecimal.ZERO;
		long quarantined = 0;
		Map<String, Long> callStatusCounts = new TreeMap<>();

		for (UsageLog event : events) {
			totalTokens += event.getTotalTokens() == null ? 0 : event.getTotalTokens();
			callStatusCounts.merge(event.getCallStatus(), 1L, Long::sum);

			String accountingStatus = event.getAccountingStatus();
			if (AccountingStatus.QUARANTINED.name().equals(accountingStatus)) {
				quarantined++;
			} else if (AccountingStatus.LEGACY_UNVERIFIED.name().equals(accountingStatus)) {
				// 기존 수집 경로는 원장 개정본이 없다. 검증되지 않은 usage_events 금액을 따로 보여 준다.
				legacy++;
				if (event.getTotalCostUsd() != null) {
					legacyCost = legacyCost.add(event.getTotalCostUsd());
				}
			} else {
				UsageAccountingValue actual = actualValues.get(event.getId());
				if (actual != null && actual.getPricingStatus() == AccountingAmount.PricingStatus.PRICED) {
					actualCost = actualCost.add(actual.getAmount());
				} else {
					unpriced++;
				}
			}
		}
		return new BreakGlassUsageResponse.Summary(events.size(), totalTokens, actualCost, unpriced, legacy,
				legacyCost, quarantined, callStatusCounts);
	}

	// metadata에서 grant id를 꺼낸다. 키가 없거나 JSON이 아니면 null이다.
	// H2는 문자열을 JSON 문자열 값으로 한 번 더 감싸 저장할 수 있어서, 문자열 값이면 한 번 더 파싱한다.
	private String taggedGrantId(String metadataJson) {
		if (metadataJson == null || metadataJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(metadataJson);
			if (node != null && node.isTextual()) {
				node = objectMapper.readTree(node.asText());
			}
			JsonNode value = node == null ? null : node.get(BreakGlassGrant.USAGE_METADATA_KEY);
			return value != null && value.isTextual() ? value.asText() : null;
		} catch (Exception exception) {
			return null;
		}
	}
}
