package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchItemResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionBatchResponse;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionEventItemRequest;
import com.tokenledgercloud.api.domain.ingestion.dto.IngestionItemStatus;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/** Batch behaviour against real services and H2; only API-key authentication is mocked. */
@SpringBootTest
class IngestionBatchIntegrationTest {

	private static final String API_KEY = "key";
	private static final String PROJECT_KEY = "support-copilot";
	private static final String ENV = "prod";

	@Autowired
	private IngestionService ingestionService;

	@MockitoBean
	private ProjectApiKeyAuthenticator projectApiKeyAuthenticator;

	@MockitoSpyBean
	private UsageLogRepository usageLogRepository;

	@BeforeEach
	void setUp() {
		given(projectApiKeyAuthenticator.authenticate(eq(API_KEY), eq(PROJECT_KEY), eq(ENV)))
			.willReturn(new AuthenticatedProjectApiKey("org-1", "project-1", "key-1", ENV));
		usageLogRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		Mockito.reset(usageLogRepository);
		usageLogRepository.deleteAll();
	}

	private static IngestionEventItemRequest item(String requestId, Long promptTokens) {
		return new IngestionEventItemRequest(
			requestId, "openai", "gpt-4o-mini",
			promptTokens, 400L, 0L, null, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", null, null,
			OffsetDateTime.parse("2026-05-06T10:00:00Z"), null
		);
	}

	private IngestionBatchResponse send(List<IngestionEventItemRequest> items) {
		return ingestionService.collectBatch(API_KEY, new IngestionBatchRequest(PROJECT_KEY, ENV, items));
	}

	private static Map<String, IngestionBatchItemResponse> byRequestId(IngestionBatchResponse response) {
		Map<String, IngestionBatchItemResponse> map = new LinkedHashMap<>();
		response.items().forEach(item -> map.put(item.requestId(), item));
		return map;
	}

	// --- reordered batches ---

	@Test
	void sameBatchSentTwiceInReverseOrderGivesTheSamePerItemOutcome() {
		send(List.of(item("req-conflict", 100L))); // pre-existing row the batch will collide with
		List<IngestionEventItemRequest> batch = List.of(
			item("req-1", 100L),
			item("req-2", 200L),
			item("req-bad", null), // violates @NotNull promptTokens
			item("req-conflict", 999L), // same key as the stored row, different payload
			item("req-3", 300L)
		);
		List<IngestionEventItemRequest> reversed = new ArrayList<>(batch);
		Collections.reverse(reversed);

		IngestionBatchResponse first = send(batch);
		IngestionBatchResponse second = send(reversed);

		// first send
		assertThat(first.items()).extracting(IngestionBatchItemResponse::requestId)
			.containsExactly("req-1", "req-2", "req-bad", "req-conflict", "req-3");
		assertThat(first.createdCount()).isEqualTo(3);
		assertThat(first.duplicateCount()).isZero();
		assertThat(first.acceptedCount()).isEqualTo(3);
		assertThat(first.rejectedCount()).isEqualTo(2);

		// reversed resend lists items in the new request order ...
		assertThat(second.items()).extracting(IngestionBatchItemResponse::requestId)
			.containsExactly("req-3", "req-conflict", "req-bad", "req-2", "req-1");
		assertThat(second.items()).extracting(IngestionBatchItemResponse::index).containsExactly(0, 1, 2, 3, 4);
		assertThat(second.createdCount()).isZero();
		assertThat(second.duplicateCount()).isEqualTo(3);
		assertThat(second.acceptedCount()).isEqualTo(3);
		assertThat(second.rejectedCount()).isEqualTo(2);

		// ... but each item has the same outcome: accepted ones resolve to the same stored event, rejected ones
		// keep their code, message and retryable flag.
		Map<String, IngestionBatchItemResponse> firstById = byRequestId(first);
		Map<String, IngestionBatchItemResponse> secondById = byRequestId(second);
		for (String requestId : List.of("req-1", "req-2", "req-3")) {
			assertThat(firstById.get(requestId).status()).isEqualTo(IngestionItemStatus.CREATED);
			assertThat(secondById.get(requestId).status()).isEqualTo(IngestionItemStatus.DUPLICATE);
			assertThat(secondById.get(requestId).usageEventId()).isEqualTo(firstById.get(requestId).usageEventId());
		}
		for (String requestId : List.of("req-bad", "req-conflict")) {
			IngestionBatchItemResponse a = firstById.get(requestId);
			IngestionBatchItemResponse b = secondById.get(requestId);
			assertThat(a.status()).isEqualTo(IngestionItemStatus.REJECTED);
			assertThat(b).usingRecursiveComparison().ignoringFields("index").isEqualTo(a);
		}
		assertThat(firstById.get("req-bad").code()).isEqualTo("COMMON-400");
		assertThat(firstById.get("req-bad").retryable()).isFalse();
		assertThat(firstById.get("req-conflict").code()).isEqualTo("INGESTION-409");
		assertThat(firstById.get("req-conflict").retryable()).isFalse();

		// legacy fields still describe the same thing
		assertThat(first.rejectedItems()).extracting(r -> r.requestId()).containsExactly("req-bad", "req-conflict");
		assertThat(first.rejectedItems()).extracting(r -> r.index()).containsExactly(2, 3);
		assertThat(usageLogRepository.count()).isEqualTo(4);
	}

	@Test
	void twoBatchesWithTheSameItemsInOppositeOrderRunningAtOnceCreateEachItemOnce() throws Exception {
		for (int round = 0; round < 5; round++) {
			List<IngestionEventItemRequest> forward = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				forward.add(item("req-" + round + "-" + i, 100L + i));
			}
			List<IngestionEventItemRequest> reversed = new ArrayList<>(forward);
			Collections.reverse(reversed);

			ExecutorService executor = Executors.newFixedThreadPool(2);
			CountDownLatch start = new CountDownLatch(1);
			try {
				Future<IngestionBatchResponse> a = executor.submit(() -> {
					start.await();
					return send(forward);
				});
				Future<IngestionBatchResponse> b = executor.submit(() -> {
					start.await();
					return send(reversed);
				});
				start.countDown();
				IngestionBatchResponse first = a.get(30, TimeUnit.SECONDS);
				IngestionBatchResponse second = b.get(30, TimeUnit.SECONDS);

				assertThat(first.rejectedCount()).isZero();
				assertThat(second.rejectedCount()).isZero();
				// every item was created by exactly one of the two batches and is a duplicate in the other
				assertThat(first.createdCount() + second.createdCount()).isEqualTo(20);
				assertThat(first.duplicateCount() + second.duplicateCount()).isEqualTo(20);
				Map<String, IngestionBatchItemResponse> firstById = byRequestId(first);
				Map<String, IngestionBatchItemResponse> secondById = byRequestId(second);
				for (IngestionEventItemRequest request : forward) {
					IngestionBatchItemResponse x = firstById.get(request.requestId());
					IngestionBatchItemResponse y = secondById.get(request.requestId());
					assertThat(x.usageEventId()).isEqualTo(y.usageEventId());
					assertThat(List.of(x.status(), y.status()))
						.containsExactlyInAnyOrder(IngestionItemStatus.CREATED, IngestionItemStatus.DUPLICATE);
				}
				assertThat(usageLogRepository.count()).isEqualTo(20);
			} finally {
				executor.shutdownNow();
			}
			usageLogRepository.deleteAll();
		}
	}

	// --- partial failure ---

	private static void failInsertFor(UsageLogRepository repository, String requestId, RuntimeException failure) {
		doThrow(failure).when(repository).saveAndFlush(argThat((UsageLog log) -> requestId.equals(log.getRequestId())));
	}

	@Test
	void lockFailuresRejectOnlyTheAffectedItemsAsRetryableAndTheRestAreStored() {
		failInsertFor(usageLogRepository, "req-lock", new CannotAcquireLockException("lock wait timeout"));
		failInsertFor(usageLogRepository, "req-deadlock", new DeadlockLoserDataAccessException("deadlock", null));
		List<IngestionEventItemRequest> batch = List.of(
			item("req-ok-1", 100L), item("req-lock", 200L), item("req-deadlock", 300L), item("req-ok-2", 400L));

		IngestionBatchResponse response = send(batch);

		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.REJECTED, IngestionItemStatus.REJECTED,
			IngestionItemStatus.CREATED);
		assertThat(response.items().get(1).code()).isEqualTo("INGESTION-503");
		assertThat(response.items().get(1).retryable()).isTrue();
		assertThat(response.items().get(2).code()).isEqualTo("INGESTION-503");
		assertThat(response.items().get(2).retryable()).isTrue();
		assertThat(response.rejectedItems()).extracting(r -> r.retryable()).containsExactly(true, true);
		assertThat(response.acceptedCount()).isEqualTo(2);
		assertThat(response.rejectedCount()).isEqualTo(2);
		assertThat(usageLogRepository.findAll()).extracting(UsageLog::getRequestId)
			.containsExactlyInAnyOrder("req-ok-1", "req-ok-2");

		// the client re-sends the whole batch: stored items are duplicates, the failed ones now succeed
		Mockito.reset(usageLogRepository);
		IngestionBatchResponse retry = send(batch);

		assertThat(retry.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.DUPLICATE, IngestionItemStatus.CREATED, IngestionItemStatus.CREATED,
			IngestionItemStatus.DUPLICATE);
		assertThat(retry.rejectedCount()).isZero();
		assertThat(usageLogRepository.count()).isEqualTo(4);
	}

	@Test
	void unexpectedNonLockFailurePropagatesAndEarlierItemsStayStoredAndSafeToResend() {
		failInsertFor(usageLogRepository, "req-boom", new IllegalStateException("boom"));
		List<IngestionEventItemRequest> batch = List.of(item("req-ok-1", 100L), item("req-boom", 200L));

		assertThatThrownBy(() -> send(batch)).isInstanceOf(IllegalStateException.class);
		assertThat(usageLogRepository.findAll()).extracting(UsageLog::getRequestId).containsExactly("req-ok-1");

		Mockito.reset(usageLogRepository);
		IngestionBatchResponse retry = send(batch);

		assertThat(retry.items()).extracting(IngestionBatchItemResponse::status)
			.containsExactly(IngestionItemStatus.DUPLICATE, IngestionItemStatus.CREATED);
	}

	// --- duplicates inside one batch, null items, authentication ---

	@Test
	void repeatedKeyInsideOneBatchIsDuplicateForSamePayloadAndConflictForDifferentPayload() {
		IngestionBatchResponse response = send(List.of(
			item("req-x", 100L), item("req-x", 100L), item("req-x", 999L)));

		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.DUPLICATE, IngestionItemStatus.REJECTED);
		assertThat(response.items().get(1).usageEventId()).isEqualTo(response.items().get(0).usageEventId());
		assertThat(response.items().get(2).code()).isEqualTo("INGESTION-409");
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void nullAndInvalidItemsAreRejectedWithoutStoppingTheBatch() {
		IngestionBatchResponse response = send(Arrays.asList(item("req-1", 100L), null, item("req-2", 200L)));

		assertThat(response.items()).extracting(IngestionBatchItemResponse::status).containsExactly(
			IngestionItemStatus.CREATED, IngestionItemStatus.REJECTED, IngestionItemStatus.CREATED);
		assertThat(response.items().get(1).code()).isEqualTo("COMMON-400");
		assertThat(usageLogRepository.count()).isEqualTo(2);
	}

	@Test
	void authenticationFailureRejectsTheWholeBatchAndStoresNothing() {
		given(projectApiKeyAuthenticator.authenticate(eq("bad-key"), any(), any()))
			.willThrow(new ApiException(ErrorCode.UNAUTHORIZED, "Invalid project API key."));

		assertThatThrownBy(() -> ingestionService.collectBatch("bad-key",
			new IngestionBatchRequest(PROJECT_KEY, ENV, List.of(item("req-1", 100L), item("req-2", 200L)))))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
		assertThat(usageLogRepository.count()).isZero();
	}
}
