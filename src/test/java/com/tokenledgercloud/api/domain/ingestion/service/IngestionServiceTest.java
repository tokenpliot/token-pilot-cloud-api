package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchItemResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionItemStatus;
import com.tokenledgercloud.api.domain.ingestion.dto.RejectedIngestionItemResponse;
import com.tokenledgercloud.api.domain.ingestion.config.IngestionProperties;
import com.tokenledgercloud.api.domain.ingestion.validation.IngestionMetadataPolicy;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateResult;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogResponse;
import com.tokenledgercloud.api.domain.usage.service.UsageLogService;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

	@Mock
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	@Mock
	private UsageLogService usageLogService;

	@Mock
	private Validator validator;

	@Spy
	private IngestionMetadataPolicy metadataPolicy = new IngestionMetadataPolicy(IngestionProperties.defaults());

	@InjectMocks
	private IngestionService ingestionService;

	@BeforeEach
	void authenticate() {
		given(projectApiKeyAuthenticator.authenticate(eq("key"), eq("support-copilot"), eq("prod")))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", "prod"));
	}

	private static IngestionEventRequest event(String requestId, String eventId) {
		return new IngestionEventRequest(
			"support-copilot", "prod", requestId, "openai", "gpt-4o-mini",
			1200L, 400L, 0L, null, 1600L,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, new BigDecimal("0.00042"),
			null, "2026-05-01", null, null,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), eventId
		);
	}

	private static IngestionEventItemRequest item(String requestId, String eventId) {
		return new IngestionEventItemRequest(
			requestId, "openai", "gpt-4o-mini",
			1200L, 400L, 0L, null, 1600L,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, new BigDecimal("0.00042"),
			null, "2026-05-01", null, null,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), eventId
		);
	}

	private static UsageLogCreateResult result(String id, boolean duplicate) {
		UsageLogResponse log = new UsageLogResponse(id, "org-1", "project-1", "key-1", "prod", "req", "openai",
			"gpt-4o-mini", 1200L, 400L, 0L, 0L, 1600L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.ZERO, null, "2026-05-01", "sdk", null, null);
		return new UsageLogCreateResult(log, duplicate);
	}

	@Test
	void newEventReturnsExistingFieldsPlusDuplicateFalseAndRequestId() {
		given(usageLogService.createIdempotent(any())).willReturn(result("usage-1", false));

		IngestionEventResponse response = ingestionService.collectEvent("key", event("req_123", null));

		assertThat(response).isEqualTo(new IngestionEventResponse("usage-1", true, false, "req_123"));
	}

	@Test
	void retransmittedEventReturnsStoredResultMarkedDuplicate() {
		given(usageLogService.createIdempotent(any())).willReturn(result("usage-1", true));

		IngestionEventResponse response = ingestionService.collectEvent("key", event("req_123", "evt-1"));

		assertThat(response.eventId()).isEqualTo("usage-1");
		assertThat(response.accepted()).isTrue();
		assertThat(response.duplicate()).isTrue();
	}

	@Test
	void eventIdIsPassedToTheUsageLogRequest() {
		given(usageLogService.createIdempotent(any())).willReturn(result("usage-1", false));

		ingestionService.collectEvent("key", event("req_123", "evt-1"));

		ArgumentCaptor<UsageLogCreateRequest> captor = ArgumentCaptor.forClass(UsageLogCreateRequest.class);
		verify(usageLogService).createIdempotent(captor.capture());
		assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
		assertThat(captor.getValue().requestId()).isEqualTo("req_123");
		assertThat(captor.getValue().projectId()).isEqualTo("project-1");
	}

	@Test
	void conflictOnSingleEventPropagatesAsIdempotencyConflict() {
		given(usageLogService.createIdempotent(any())).willThrow(new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT));

		assertThatThrownBy(() -> ingestionService.collectEvent("key", event("req_123", "evt-1")))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode().getCode()).isEqualTo("INGESTION-409"));
	}

	@Test
	void batchRejectsOnlyTheConflictingItem() {
		given(usageLogService.createIdempotent(any()))
			.willReturn(result("usage-1", false))
			.willThrow(new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT))
			.willReturn(result("usage-3", true));

		IngestionBatchResponse response = ingestionService.collectBatch("key", new IngestionBatchRequest(
			"support-copilot", "prod",
			List.of(item("req-1", null), item("req-2", "evt-2"), item("req-3", null))
		));

		assertThat(response.acceptedCount()).isEqualTo(2);
		assertThat(response.rejectedCount()).isEqualTo(1);
		assertThat(response.rejectedItems()).singleElement().satisfies(rejected -> {
			assertThat(rejected.index()).isEqualTo(1);
			assertThat(rejected.requestId()).isEqualTo("req-2");
			assertThat(rejected.code()).isEqualTo("INGESTION-409");
		});
		verify(usageLogService, times(3)).createIdempotent(any());
	}

	@Test
	void batchListsEveryItemInRequestOrderWithStatusAndRetryable() {
		given(usageLogService.createIdempotent(any()))
			.willReturn(result("usage-1", false))
			.willReturn(result("usage-2", true))
			.willThrow(new ApiException(ErrorCode.INGESTION_RETRY_LATER))
			.willThrow(new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT));

		IngestionBatchResponse response = ingestionService.collectBatch("key", new IngestionBatchRequest(
			"support-copilot", "prod",
			List.of(item("req-1", null), item("req-2", null), item("req-3", null), item("req-4", "evt-4"))
		));

		assertThat(response.items()).extracting(IngestionBatchItemResponse::index).containsExactly(0, 1, 2, 3);
		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.DUPLICATE, IngestionItemStatus.REJECTED,
			IngestionItemStatus.REJECTED);
		assertThat(response.items()).extracting(IngestionBatchItemResponse::usageEventId)
			.containsExactly("usage-1", "usage-2", null, null);
		assertThat(response.items()).extracting(IngestionBatchItemResponse::retryable)
			.containsExactly(false, false, true, false);
		assertThat(response.items()).extracting(IngestionBatchItemResponse::code)
			.containsExactly(null, null, "INGESTION-503", "INGESTION-409");
		assertThat(response.createdCount()).isEqualTo(1);
		assertThat(response.duplicateCount()).isEqualTo(1);
		assertThat(response.acceptedCount()).isEqualTo(2);
		assertThat(response.rejectedCount()).isEqualTo(2);
		assertThat(response.rejectedItems()).extracting(RejectedIngestionItemResponse::retryable)
			.containsExactly(true, false);
	}

	@Test
	void batchRejectsNullItemWithoutCallingTheService() {
		given(usageLogService.createIdempotent(any())).willReturn(result("usage-1", false));

		IngestionBatchResponse response = ingestionService.collectBatch("key", new IngestionBatchRequest(
			"support-copilot", "prod", java.util.Arrays.asList(item("req-1", null), null)
		));

		assertThat(response.items()).hasSize(2);
		assertThat(response.items().get(1).status()).isEqualTo(IngestionItemStatus.REJECTED);
		assertThat(response.items().get(1).requestId()).isNull();
		assertThat(response.items().get(1).code()).isEqualTo("COMMON-400");
		assertThat(response.items().get(1).retryable()).isFalse();
		verify(usageLogService, times(1)).createIdempotent(any());
	}
}
