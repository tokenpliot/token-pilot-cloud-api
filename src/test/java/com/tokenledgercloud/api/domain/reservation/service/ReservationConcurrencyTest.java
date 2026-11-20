package com.tokenledgercloud.api.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.ReservationResult;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand.Cost;
import com.tokenledgercloud.api.domain.reservation.SettlementCommand.Usage;
import com.tokenledgercloud.api.domain.reservation.SettlementResult;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationCounter;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * 여러 요청이 동시에 들어와도 한도를 넘겨 예약하지 않고, 정산이 두 번 잡히지 않는지 확인한다.
 * 스레드마다 독립 트랜잭션이라 여러 서버 인스턴스가 같은 DB를 쓰는 상황과 같은 경쟁이 생긴다.
 * H2 기반이다. 실제 UNIQUE 제약과 행 잠금은 걸리지만 MySQL의 잠금·오류 거동 자체를 검증하지는 않는다.
 */
// 설정을 따로 주지 않아 다른 기본 @SpringBootTest(IngestionApiKeyFailureIntegrationTest 등)와 같은 캐시 컨텍스트를 쓴다.
// 테스트마다 다른 설정을 주면 Spring 컨텍스트가 하나씩 더 캐시되어 전체 테스트가 메모리 부족으로 실패한다.
// 기본 테스트 설정은 Flyway를 끄고(H2 + 엔티티 기준 스키마), 이 테스트는 예약 표만 쓰고 비운다.
@SpringBootTest
class ReservationConcurrencyTest {

	private static final String PROJECT = "project-1";
	private static final String ENV = "prod";
	private static final String PERIOD = "2026-11";
	private static final int THREADS = 8;
	private static final int ROUNDS = 5;
	private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 11, 10, 12, 5);
	private static final Instant OCCURRED = Instant.parse("2026-11-10T12:00:06Z");

	@Autowired ReservationService service;
	@Autowired BudgetReservationRepository reservationRepository;
	@Autowired BudgetReservationCounterRepository counterRepository;
	@Autowired BudgetReservationSettlementRepository settlementRepository;

	@AfterEach
	void cleanTables() {
		settlementRepository.deleteAll();
		reservationRepository.deleteAll();
		counterRepository.deleteAll();
	}

	// 환경 예산 limit, 프로젝트 예산은 그 두 배. 환경 쪽이 먼저 모자란다.
	private static ReservationCommand reserve(String key, String cost, String limit) {
		BigDecimal environmentLimit = new BigDecimal(limit);
		return new ReservationCommand("org-1", PROJECT, ENV, PERIOD, "dec:req-" + key, "req-" + key, "openai",
				"gpt-test", 1000L, 500L, new BigDecimal(cost), environmentLimit.multiply(BigDecimal.TWO),
				environmentLimit, null, EXPIRES);
	}

	private static SettlementCommand settle(String id, String requestId, SettlementAction action, String cost) {
		boolean commit = action == SettlementAction.COMMIT;
		return new SettlementCommand(id, PROJECT, "stl:" + id + ":" + action, requestId, action,
				commit ? new Usage(900L, 400L, null, null) : null,
				commit ? Cost.total(new BigDecimal(cost)) : null, OCCURRED);
	}

	private BudgetReservationCounter counter() {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, ENV, PERIOD).orElseThrow();
	}

	private BudgetReservationCounter projectCounter() {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, "", PERIOD).orElseThrow();
	}

	// 결과 또는 예외 하나.
	private record Outcome<T>(T value, Throwable error) {
	}

	/** 작업마다 스레드 하나를 띄워 동시에 출발시키고, 결과를 모두 모은다. */
	private static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Outcome<T>>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return new Outcome<>(task.call(), null);
					} catch (Throwable error) {
						return new Outcome<T>(null, error);
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Outcome<T>> outcomes = new ArrayList<>();
			for (Future<Outcome<T>> future : futures) {
				outcomes.add(future.get(30, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentReservationsNeverExceedTheLimit() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			// 한도 1.00에 0.30씩 8건: 3건(0.90)만 들어갈 수 있다.
			List<Callable<ReservationResult>> tasks = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				String key = "r" + round + "-k" + i;
				tasks.add(() -> service.reserve(reserve(key, "0.30", "1.00")));
			}

			List<Outcome<ReservationResult>> outcomes = runConcurrently(tasks);

			assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
			assertThat(outcomes).filteredOn(o -> o.value().granted()).hasSize(3);
			assertThat(counter().getHeldUsd()).isEqualByComparingTo("0.90");
			assertThat(projectCounter().getHeldUsd()).isEqualByComparingTo("0.90");
			assertThat(reservationRepository.count()).isEqualTo(3);
			cleanTables();
		}
	}

	@Test
	void concurrentRetriesOfTheSameReservationHoldTheAmountOnce() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			String key = "same-" + round;
			List<Callable<ReservationResult>> tasks = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				tasks.add(() -> service.reserve(reserve(key, "0.30", "1.00")));
			}

			List<Outcome<ReservationResult>> outcomes = runConcurrently(tasks);

			assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
			assertThat(outcomes).filteredOn(o -> !o.value().duplicate()).hasSize(1);
			assertThat(outcomes.stream().map(o -> o.value().reservation().getId()).distinct()).hasSize(1);
			assertThat(counter().getHeldUsd()).isEqualByComparingTo("0.30");
			assertThat(reservationRepository.count()).isEqualTo(1);
			cleanTables();
		}
	}

	@Test
	void concurrentDuplicateCommitsCountTheCostOnce() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			ReservationResult reserved = service.reserve(reserve("c-" + round, "0.30", "1.00"));
			String id = reserved.reservation().getId();
			String requestId = reserved.reservation().getRequestId();
			List<Callable<SettlementResult>> tasks = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				tasks.add(() -> service.settle(settle(id, requestId, SettlementAction.COMMIT, "0.25")));
			}

			List<Outcome<SettlementResult>> outcomes = runConcurrently(tasks);

			assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
			assertThat(outcomes).filteredOn(o -> !o.value().duplicate()).hasSize(1);
			assertThat(counter().getHeldUsd()).isEqualByComparingTo("0");
			assertThat(counter().getCommittedUsd()).isEqualByComparingTo("0.25");
			assertThat(projectCounter().getCommittedUsd()).isEqualByComparingTo("0.25");
			assertThat(settlementRepository.count()).isEqualTo(1);
			cleanTables();
		}
	}

	@Test
	void concurrentCommitAndReleaseLetExactlyOneWin() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			ReservationResult reserved = service.reserve(reserve("cr-" + round, "0.30", "1.00"));
			String id = reserved.reservation().getId();
			String requestId = reserved.reservation().getRequestId();
			List<Callable<SettlementResult>> tasks = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				boolean commit = i % 2 == 0;
				tasks.add(() -> service.settle(commit
						? settle(id, requestId, SettlementAction.COMMIT, "0.25")
						: settle(id, requestId, SettlementAction.RELEASE, null)));
			}

			List<Outcome<SettlementResult>> outcomes = runConcurrently(tasks);

			// 이긴 쪽 1건만 새로 적용되고, 같은 종류는 재전송, 다른 종류는 409다.
			assertThat(outcomes).filteredOn(o -> o.value() != null && !o.value().duplicate()).hasSize(1);
			assertThat(outcomes).filteredOn(o -> o.error() != null).allSatisfy(o ->
					assertThat(o.error()).isInstanceOfSatisfying(ApiException.class,
							e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)));
			assertThat(outcomes).filteredOn(o -> o.error() != null).hasSize(THREADS / 2);
			// 어느 쪽이 이겼든 잡아둔 금액은 전부 풀리고, 비용은 0 또는 0.25 한 번만 잡힌다.
			BudgetReservationCounter counter = counter();
			assertThat(counter.getHeldUsd()).isEqualByComparingTo("0");
			assertThat(counter.getCommittedUsd().compareTo(BigDecimal.ZERO) == 0
					|| counter.getCommittedUsd().compareTo(new BigDecimal("0.25")) == 0).isTrue();
			cleanTables();
		}
	}
}
