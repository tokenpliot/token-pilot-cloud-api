package com.tokenledgercloud.api.domain.ingestion.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.RejectedIngestionItemResponse;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateResult;
import com.tokenledgercloud.api.domain.usage.service.UsageLogService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IngestionService {

	private final ProjectApiKeyAuthenticator projectApiKeyAuthenticator;
	private final UsageLogService usageLogService;
	private final Validator validator;
	private final ObjectMapper objectMapper = new ObjectMapper();

	// No surrounding transaction: authentication and each usage-log write run in their own short transactions.
	public IngestionEventResponse collectEvent(String rawApiKey, IngestionEventRequest request) {
		AuthenticatedProjectApiKey auth = projectApiKeyAuthenticator.authenticate(
			rawApiKey,
			request.projectKey(),
			request.environment()
		);

		UsageLogCreateResult result = usageLogService.createIdempotent(toUsageLogCreateRequest(auth, request));
		return new IngestionEventResponse(result.log().id(), true, result.duplicate(), request.requestId());
	}

	@Transactional
	public IngestionBatchResponse collectBatch(String rawApiKey, IngestionBatchRequest request) {
		AuthenticatedProjectApiKey auth = projectApiKeyAuthenticator.authenticate(
			rawApiKey,
			request.projectKey(),
			request.environment()
		);

		int acceptedCount = 0;
		List<RejectedIngestionItemResponse> rejectedItems = new ArrayList<>();

		for (int i = 0; i < request.items().size(); i++) {
			IngestionEventItemRequest item = request.items().get(i);
			List<String> violations = validateItem(item);
			if (!violations.isEmpty()) {
				rejectedItems.add(new RejectedIngestionItemResponse(
					i,
					item == null ? null : item.requestId(),
					ErrorCode.INVALID_INPUT.getCode(),
					String.join(", ", violations)
				));
				continue;
			}

			try {
				usageLogService.createIdempotent(toUsageLogCreateRequest(auth, request.environment(), item));
				acceptedCount++;
			} catch (ApiException exception) {
				rejectedItems.add(new RejectedIngestionItemResponse(
					i,
					item.requestId(),
					exception.getErrorCode().getCode(),
					exception.getMessage()
				));
			}
		}

		return new IngestionBatchResponse(acceptedCount, rejectedItems.size(), rejectedItems);
	}

	private List<String> validateItem(IngestionEventItemRequest item) {
		if (item == null) {
			return List.of("item must not be null");
		}

		Set<ConstraintViolation<IngestionEventItemRequest>> violations = validator.validate(item);
		return violations.stream()
			.map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
			.toList();
	}

	private UsageLogCreateRequest toUsageLogCreateRequest(
		AuthenticatedProjectApiKey auth,
		IngestionEventRequest request
	) {
		return new UsageLogCreateRequest(
			auth.organizationId(),
			auth.projectId(),
			auth.apiKeyId(),
			request.environment(),
			request.requestId(),
			request.provider(),
			request.model(),
			request.promptTokens(),
			request.completionTokens(),
			request.reasoningTokens(),
			request.cachedPromptTokens(),
			request.totalTokens(),
			zeroIfNull(request.promptCostUsd()),
			zeroIfNull(request.completionCostUsd()),
			request.reasoningCostUsd(),
			request.cachedPromptCostUsd(),
			request.totalCostUsd(),
			request.pricingPlanId(),
			request.pricingVersion(),
			sourceType(request.sourceType()),
			metadataJson(request.metadata()),
			toUtcLocalDateTime(request.occurredAt()),
			request.eventId()
		);
	}

	private UsageLogCreateRequest toUsageLogCreateRequest(
		AuthenticatedProjectApiKey auth,
		String environment,
		IngestionEventItemRequest item
	) {
		return new UsageLogCreateRequest(
			auth.organizationId(),
			auth.projectId(),
			auth.apiKeyId(),
			environment,
			item.requestId(),
			item.provider(),
			item.model(),
			item.promptTokens(),
			item.completionTokens(),
			item.reasoningTokens(),
			item.cachedPromptTokens(),
			item.totalTokens(),
			zeroIfNull(item.promptCostUsd()),
			zeroIfNull(item.completionCostUsd()),
			item.reasoningCostUsd(),
			item.cachedPromptCostUsd(),
			item.totalCostUsd(),
			item.pricingPlanId(),
			item.pricingVersion(),
			sourceType(item.sourceType()),
			metadataJson(item.metadata()),
			toUtcLocalDateTime(item.occurredAt()),
			item.eventId()
		);
	}

	private String metadataJson(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(metadata);
		} catch (JsonProcessingException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "metadata must be JSON serializable.");
		}
	}

	private LocalDateTime toUtcLocalDateTime(OffsetDateTime occurredAt) {
		return occurredAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	private BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private String sourceType(String sourceType) {
		return sourceType == null || sourceType.isBlank() ? "sdk" : sourceType;
	}
}
