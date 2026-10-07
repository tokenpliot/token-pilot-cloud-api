package com.tokenledgercloud.api.domain.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tokenledgercloud.api.domain.ingestion.service.PayloadFingerprint;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateRequest;
import com.tokenledgercloud.api.domain.usage.dto.UsageLogCreateResult;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * Concurrency behaviour of {@link UsageLogService#createIdempotent} on H2 (schema generated from the entities,
 * so the unique constraints are real). MySQL lock behaviour is not covered by these tests.
 */
@SpringBootTest
class UsageLogServiceConcurrencyTest {

	private static final String PROJECT = "project-1";
	private static final String ENV = "prod";
	private static final int THREADS = 8;
	private static final int ROUNDS = 10;

	@Autowired
	private UsageLogService usageLogService;

	@MockitoSpyBean
	private UsageLogRepository usageLogRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@PersistenceContext
	private EntityManager entityManager;

	@BeforeEach
	@AfterEach
	void cleanTable() {
		usageLogRepository.deleteAll();
	}

	private static UsageLogCreateRequest request(String requestId, String eventId, long promptTokens) {
		return new UsageLogCreateRequest(
			"org-1", PROJECT, "key-1", ENV, requestId,
			"openai", "gpt-4o-mini",
			promptTokens, 400L, 0L, 0L, null,
			new BigDecimal("0.00018"), new BigDecimal("0.00024"), null, null, null,
			null, "2026-05-01", "sdk", null,
			LocalDateTime.of(2026, 5, 6, 10, 0),
			eventId
		);
	}

	private record Outcome(UsageLogCreateResult result, Throwable error) {

		boolean created() {
			return result != null && !result.duplicate();
		}

		boolean duplicate() {
			return result != null && result.duplicate();
		}

		boolean conflict() {
			return error instanceof ApiException api && api.getErrorCode() == ErrorCode.IDEMPOTENCY_CONFLICT;
		}
	}

	/** Starts one thread per request, releases them together and collects every outcome. */
	private List<Outcome> runConcurrently(List<UsageLogCreateRequest> requests) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(requests.size());
		CountDownLatch ready = new CountDownLatch(requests.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (UsageLogCreateRequest request : requests) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return new Outcome(usageLogService.createIdempotent(request), null);
					} catch (Throwable error) {
						return new Outcome(null, error);
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(30, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			executor.shutdownNow();
		}
	}

	private static List<UsageLogCreateRequest> identical(int count, String requestId, String eventId) {
		List<UsageLogCreateRequest> requests = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			requests.add(request(requestId, eventId, 1200L));
		}
		return requests;
	}

	@Test
	void concurrentSamePayloadByRequestIdCreatesOneRowAndRestAreDuplicates() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			List<Outcome> outcomes = runConcurrently(identical(THREADS, "req-" + round, null));

			assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
			assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
			assertThat(outcomes).filteredOn(Outcome::duplicate).hasSize(THREADS - 1);
			assertThat(outcomes.stream().map(o -> o.result().log().id()).distinct()).hasSize(1);
			assertThat(usageLogRepository.count()).isEqualTo(1);
			usageLogRepository.deleteAll();
		}
	}

	@Test
	void concurrentSamePayloadByEventIdCreatesOneRowAndRestAreDuplicates() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			List<Outcome> outcomes = runConcurrently(identical(THREADS, "req-" + round, "evt-" + round));

			assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
			assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
			assertThat(outcomes).filteredOn(Outcome::duplicate).hasSize(THREADS - 1);
			assertThat(outcomes.stream().map(o -> o.result().log().id()).distinct()).hasSize(1);
			assertThat(usageLogRepository.count()).isEqualTo(1);
			usageLogRepository.deleteAll();
		}
	}

	@Test
	void concurrentDifferentPayloadsOnSameKeyLetExactlyOneSucceed() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			List<UsageLogCreateRequest> requests = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				requests.add(request("req-" + round, "evt-" + round, 1000L + i));
			}

			List<Outcome> outcomes = runConcurrently(requests);

			assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
			assertThat(outcomes).filteredOn(Outcome::conflict).hasSize(THREADS - 1);
			assertThat(outcomes).filteredOn(Outcome::duplicate).isEmpty();
			assertThat(usageLogRepository.count()).isEqualTo(1);

			int winner = outcomes.indexOf(outcomes.stream().filter(Outcome::created).findFirst().orElseThrow());
			UsageLog stored = usageLogRepository.findAll().get(0);
			assertThat(stored.getPayloadFingerprint()).isEqualTo(PayloadFingerprint.of(requests.get(winner)));
			usageLogRepository.deleteAll();
		}
	}

	/*
	 * Same requestId, different eventId. The two unique keys (event_id and request_id) disagree about whether the
	 * requests are the same call, so the request_id key must turn the later ones into 409, never a 500.
	 * Like everything in this class this runs on H2, not MySQL: it proves the application logic against real unique
	 * constraints, but not MySQL's lock and error behaviour.
	 */

	@Test
	void sequentialNewEventIdReusingARequestIdIsConflictAgainstTheRealUniqueKeys() {
		usageLogService.createIdempotent(request("req-seq", "evt-1", 1200L));

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-seq", "evt-2", 1200L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		assertThat(usageLogRepository.count()).isEqualTo(1);
		assertThat(usageLogRepository.findAll().get(0).getEventId()).isEqualTo("evt-1");
	}

	@Test
	void concurrentSameRequestIdWithDifferentEventIdsLetExactlyOneSucceedAndTheRestConflict() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			List<UsageLogCreateRequest> requests = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				requests.add(request("req-" + round, "evt-" + round + "-" + i, 1200L));
			}

			List<Outcome> outcomes = runConcurrently(requests);

			// any outcome other than created/conflict (a 500, a retry-later) fails here with its error
			assertThat(outcomes).filteredOn(o -> !o.created() && !o.conflict())
				.as("outcomes that are neither created nor 409: %s", outcomes).isEmpty();
			assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
			assertThat(outcomes).filteredOn(Outcome::conflict).hasSize(THREADS - 1);
			assertThat(usageLogRepository.count()).isEqualTo(1);

			int winner = outcomes.indexOf(outcomes.stream().filter(Outcome::created).findFirst().orElseThrow());
			assertThat(usageLogRepository.findAll().get(0).getEventId()).isEqualTo("evt-" + round + "-" + winner);
			usageLogRepository.deleteAll();
		}
	}

	@Test
	void concurrentMixOfTwoPayloadsKeepsOnlyTheWinnersPayload() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			List<UsageLogCreateRequest> requests = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				requests.add(request("req-" + round, "evt-" + round, i % 2 == 0 ? 1000L : 2000L));
			}

			List<Outcome> outcomes = runConcurrently(requests);

			assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
			assertThat(outcomes).filteredOn(Outcome::duplicate).hasSize(THREADS / 2 - 1);
			assertThat(outcomes).filteredOn(Outcome::conflict).hasSize(THREADS / 2);
			assertThat(usageLogRepository.count()).isEqualTo(1);
			usageLogRepository.deleteAll();
		}
	}

	@Test
	void concurrentLegacyCreateAlsoCollapsesToOneRow() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return usageLogService.create(request("req-legacy", null, 1200L)).id();
				}));
			}
			start.countDown();
			List<String> ids = new ArrayList<>();
			for (Future<String> future : futures) {
				ids.add(future.get(30, TimeUnit.SECONDS));
			}

			assertThat(ids.stream().distinct()).hasSize(1);
			assertThat(usageLogRepository.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	// --- deterministic race: the pre-check misses a row that already exists, so the insert hits the unique key ---

	/** The first lookup misses (as if the winner had not committed yet); later lookups read the real table. */
	private void missRequestIdPreCheckOnce() {
		doReturn(Optional.empty())
			.doAnswer(invocation -> realLookup("requestId", invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2)))
			.when(usageLogRepository).findByProjectIdAndEnvironmentAndRequestId(any(), any(), any());
	}

	private void missEventIdPreCheckOnce() {
		doReturn(Optional.empty())
			.doAnswer(invocation -> realLookup("eventId", invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2)))
			.when(usageLogRepository).findByProjectIdAndEnvironmentAndEventId(any(), any(), any());
	}

	// Mockito cannot call the real method of a repository interface, so the "real" read goes through JPA directly.
	private Optional<UsageLog> realLookup(String keyField, String projectId, String environment, String key) {
		return entityManager.createQuery(
				"select u from UsageLog u where u.projectId = :p and u.environment = :e and u." + keyField + " = :k",
				UsageLog.class)
			.setParameter("p", projectId)
			.setParameter("e", environment)
			.setParameter("k", key)
			.getResultStream()
			.findFirst();
	}

	@Test
	void lostRaceWithSamePayloadReturnsTheWinnersRowByRequestId() {
		UsageLogCreateResult first = usageLogService.createIdempotent(request("req-1", null, 1200L));
		missRequestIdPreCheckOnce();

		UsageLogCreateResult second = usageLogService.createIdempotent(request("req-1", null, 1200L));

		assertThat(first.duplicate()).isFalse();
		assertThat(second.duplicate()).isTrue();
		assertThat(second.log().id()).isEqualTo(first.log().id());
		assertThat(usageLogRepository.count()).isEqualTo(1);
		verify(usageLogRepository, times(2)).saveAndFlush(any());
	}

	@Test
	void lostRaceWithSamePayloadReturnsTheWinnersRowByEventId() {
		UsageLogCreateResult first = usageLogService.createIdempotent(request(null, "evt-1", 1200L));
		missEventIdPreCheckOnce();

		UsageLogCreateResult second = usageLogService.createIdempotent(request(null, "evt-1", 1200L));

		assertThat(second.duplicate()).isTrue();
		assertThat(second.log().id()).isEqualTo(first.log().id());
		assertThat(usageLogRepository.count()).isEqualTo(1);
		verify(usageLogRepository, times(2)).saveAndFlush(any());
	}

	@Test
	void lostRaceWithDifferentPayloadIsConflict() {
		usageLogService.createIdempotent(request("req-1", null, 1200L));
		missRequestIdPreCheckOnce();

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", null, 9999L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void constraintViolationInsideAnOuterTransactionDoesNotPoisonIt() {
		usageLogService.createIdempotent(request("req-1", null, 1200L));
		missRequestIdPreCheckOnce();

		// A commit failure here would mean the caught violation had marked the outer transaction rollback-only.
		UsageLogCreateResult result = new TransactionTemplate(transactionManager)
			.execute(status -> usageLogService.createIdempotent(request("req-1", null, 1200L)));

		assertThat(result).isNotNull();
		assertThat(result.duplicate()).isTrue();
	}

	@Test
	void unmatchedUniqueViolationIsARetryableErrorNotAServerError() {
		usageLogService.createIdempotent(request("req-1", null, 1200L));
		doReturn(Optional.empty())
			.when(usageLogRepository).findByProjectIdAndEnvironmentAndRequestId(any(), any(), any());

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", null, 1200L)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INGESTION_RETRY_LATER);
				assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
			});
		assertThat(usageLogRepository.count()).isEqualTo(1);
	}

	@Test
	void nonUniqueIntegrityViolationIsNotMaskedAsRetryable() {
		// eventId longer than the column: the insert fails for a reason other than a unique key.
		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", "e".repeat(101), 1200L)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(usageLogRepository.count()).isZero();
	}

	@Test
	void stubbingDoesNotLeakBetweenTests() {
		assertThat(usageLogService.createIdempotent(request("req-leak", null, 1L)).duplicate()).isFalse();
		assertThat(usageLogService.createIdempotent(request("req-leak", null, 1L)).duplicate()).isTrue();
	}

	@Test
	void deadlockOnInsertIsMappedToRetryableError() {
		doThrow(new DeadlockLoserDataAccessException("deadlock", null))
			.when(usageLogRepository).saveAndFlush(any());

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", null, 1200L)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INGESTION_RETRY_LATER);
				assertThat(e.getErrorCode().isRetryable()).isTrue();
			});
	}

	@Test
	void lockTimeoutOnInsertIsMappedToRetryableError() {
		doThrow(new CannotAcquireLockException("lock wait timeout"))
			.when(usageLogRepository).saveAndFlush(any());

		assertThatThrownBy(() -> usageLogService.create(request("req-1", null, 1200L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INGESTION_RETRY_LATER));
	}

	@Test
	void lockFailureOnPreCheckIsMappedToRetryableError() {
		doThrow(new PessimisticLockingFailureException("lock"))
			.when(usageLogRepository).findByProjectIdAndEnvironmentAndRequestId(any(), any(), any());

		assertThatThrownBy(() -> usageLogService.createIdempotent(request("req-1", null, 1200L)))
			.isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INGESTION_RETRY_LATER));
	}
}
