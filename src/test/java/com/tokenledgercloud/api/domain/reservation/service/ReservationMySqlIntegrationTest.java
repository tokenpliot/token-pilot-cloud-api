package com.tokenledgercloud.api.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
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
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * 예약·정산을 실제 MySQL에서 확인한다(이슈 #17). H2 테스트({@link ReservationConcurrencyTest})가 못 보는 것:
 * <ul>
 *   <li>V1~V11 Flyway 스키마와 엔티티 매핑이 맞는지 ({@code ddl-auto=validate})</li>
 *   <li>InnoDB 행 잠금(SELECT ... FOR UPDATE)으로 동시 예약이 한도를 넘지 않는지</li>
 *   <li>{@code utf8mb4_bin} 정렬이라 {@code Prod}와 {@code prod}가 다른 범위인지</li>
 *   <li>같은 키가 동시에 들어올 때 MySQL의 UNIQUE 대기·데드락이 재시도 가능 503으로 끝나는지</li>
 * </ul>
 *
 * <p>빈 일회용 MySQL DB가 필요하다. {@code RESERVATION_TEST_MYSQL_URL}이 없으면 건너뛴다.
 * 예: {@code jdbc:mysql://localhost:3306/token_pilot_test?serverTimezone=UTC}
 * 테스트마다 새 프로젝트 id를 쓰고 끝나면 그 프로젝트의 행만 지운다.
 */
@EnabledIfEnvironmentVariable(named = "RESERVATION_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {
		"spring.datasource.url=${RESERVATION_TEST_MYSQL_URL}",
		"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
		"spring.datasource.username=${RESERVATION_TEST_MYSQL_USER:root}",
		"spring.datasource.password=${RESERVATION_TEST_MYSQL_PASSWORD:}",
		// validate: V10 예약 테이블을 포함한 모든 엔티티 매핑이 실제 Flyway 스키마와 맞는지 확인한다.
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=true",
		"spring.flyway.baseline-on-migrate=false",
		// MySQL 기본값과 같다. 쓰기 트랜잭션은 ReservationTx가 READ_COMMITTED로 따로 연다.
		"spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
class ReservationMySqlIntegrationTest {

	private static final int THREADS = 8;
	private static final Instant OCCURRED = Instant.parse("2026-11-15T00:00:06Z");

	@Autowired ReservationService service;
	@Autowired ReservationExpiryService expiryService;
	@Autowired BudgetReservationRepository reservationRepository;
	@Autowired BudgetReservationCounterRepository counterRepository;
	@Autowired JdbcTemplate jdbc;

	private String project;
	private String period;

	@BeforeEach
	void createProject() {
		// FK(budget_reservation_counters → projects) 때문에 실제 프로젝트 행이 있어야 한다.
		project = UUID.randomUUID().toString();
		period = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
		jdbc.update("insert into projects(id, organization_id, project_key, name, status) values(?,?,?,?,?)",
				project, "mysql-org", "rsv-" + project.substring(0, 8), "reservation mysql test", "ACTIVE");
	}

	@AfterEach
	void dropProject() {
		jdbc.update("delete from budget_reservation_settlements where project_id = ?", project);
		jdbc.update("delete from budget_reservations where project_id = ?", project);
		jdbc.update("delete from budget_reservation_counters where project_id = ?", project);
		jdbc.update("delete from projects where id = ?", project);
	}

	// 환경 예산 limit, 프로젝트 예산은 그 두 배.
	private ReservationCommand reserve(String key, String environment, String cost, String limit,
			LocalDateTime expiresAt) {
		BigDecimal environmentLimit = new BigDecimal(limit);
		return new ReservationCommand("mysql-org", project, environment, period, "dec:" + key, "req-" + key,
				"openai", "gpt-test", 1000L, 500L, new BigDecimal(cost), environmentLimit.multiply(BigDecimal.TWO),
				environmentLimit, null, expiresAt);
	}

	private ReservationCommand reserve(String key, String cost, String limit) {
		return reserve(key, "prod", cost, limit, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
	}

	private SettlementCommand commit(String reservationId, String requestId, String cost) {
		return new SettlementCommand(reservationId, project, "stl:" + reservationId, requestId,
				SettlementAction.COMMIT, new Usage(900L, 400L, null, null), Cost.total(new BigDecimal(cost)), OCCURRED);
	}

	private BudgetReservationCounter counter(String environmentKey) {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(project, environmentKey, period)
				.orElseThrow();
	}

	private record Outcome<T>(T value, Throwable error) {
	}

	/** 작업마다 스레드 하나를 띄워 동시에 출발시키고, 결과와 예외를 모두 모은다. */
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
				outcomes.add(future.get(60, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentReservationsNeverExceedTheLimitWithInnoDbRowLocks() throws Exception {
		// 한도 1.00에 0.30씩 8건: 3건(0.90)만 들어갈 수 있다. 카운터 행도 이 경쟁 안에서 처음 만들어진다.
		List<Callable<ReservationResult>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			String key = "limit-" + i;
			tasks.add(() -> service.reserve(reserve(key, "0.30", "1.00")));
		}

		List<Outcome<ReservationResult>> outcomes = runConcurrently(tasks);

		assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
		assertThat(outcomes).filteredOn(o -> o.value().granted()).hasSize(3);
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.90");
		assertThat(counter("").getHeldUsd()).isEqualByComparingTo("0.90");
		assertThat(jdbc.queryForObject("select count(*) from budget_reservations where project_id = ?",
				Integer.class, project)).isEqualTo(3);
	}

	@Test
	void concurrentRetriesOfTheSameReservationHoldTheAmountOnce() throws Exception {
		// MySQL은 같은 UNIQUE 키에 동시에 INSERT하면 데드락이 날 수 있다. 그 경우 재시도 가능한 503이어야 하고,
		// 어느 쪽이든 예약은 한 건, 잡힌 금액도 한 번이어야 한다.
		List<Callable<ReservationResult>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			tasks.add(() -> service.reserve(reserve("same-key", "0.30", "1.00")));
		}

		List<Outcome<ReservationResult>> outcomes = runConcurrently(tasks);

		assertThat(outcomes).filteredOn(o -> o.error() != null).allSatisfy(o ->
				assertThat(o.error()).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INGESTION_RETRY_LATER)));
		assertThat(outcomes).filteredOn(o -> o.value() != null && !o.value().duplicate()).hasSize(1);
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.30");
		assertThat(jdbc.queryForObject("select count(*) from budget_reservations where project_id = ?",
				Integer.class, project)).isEqualTo(1);

		// 503을 받은 쪽이 다시 보내면 같은 예약을 재전송으로 받는다.
		ReservationResult retried = service.reserve(reserve("same-key", "0.30", "1.00"));
		assertThat(retried.duplicate()).isTrue();
	}

	@Test
	void concurrentDuplicateCommitsCountTheCostOnce() throws Exception {
		ReservationResult reserved = service.reserve(reserve("commit", "0.30", "1.00"));
		String id = reserved.reservation().getId();
		String requestId = reserved.reservation().getRequestId();
		List<Callable<SettlementResult>> tasks = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			tasks.add(() -> service.settle(commit(id, requestId, "0.25")));
		}

		List<Outcome<SettlementResult>> outcomes = runConcurrently(tasks);

		assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
		assertThat(outcomes).filteredOn(o -> !o.value().duplicate()).hasSize(1);
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0");
		assertThat(counter("prod").getCommittedUsd()).isEqualByComparingTo("0.25");
		assertThat(counter("").getCommittedUsd()).isEqualByComparingTo("0.25");
		assertThat(jdbc.queryForObject("select count(*) from budget_reservation_settlements where project_id = ?",
				Integer.class, project)).isEqualTo(1);
	}

	@Test
	void environmentsAndIdempotencyKeysAreCaseSensitive() {
		// utf8mb4_bin이 아니면 Prod와 prod가 같은 카운터로 합쳐지거나 UNIQUE 오류가 난다.
		LocalDateTime expires = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5);
		ReservationResult lower = service.reserve(reserve("case-key", "prod", "0.30", "0.50", expires));
		ReservationResult upper = service.reserve(reserve("CASE-KEY", "Prod", "0.30", "0.50", expires));

		assertThat(lower.granted()).isTrue();
		assertThat(upper.granted()).isTrue();
		assertThat(upper.duplicate()).isFalse();
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.30");
		assertThat(counter("Prod").getHeldUsd()).isEqualByComparingTo("0.30");
		// 프로젝트 범위는 두 환경을 합친다.
		assertThat(counter("").getHeldUsd()).isEqualByComparingTo("0.60");
	}

	@Test
	void expiredReservationIsKeptAsDebtAndLateCommitStillSettles() {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		ReservationResult reserved = service.reserve(reserve("expired", "prod", "0.30", "1.00", now.minusMinutes(1)));
		String id = reserved.reservation().getId();

		ExpiryResult expired = expiryService.expire(now, 500);

		assertThat(expired.processed()).isGreaterThanOrEqualTo(1);
		assertThat(reservationRepository.findById(id).orElseThrow().getState())
				.isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		// 결과를 모르니 0원으로 풀지 않는다.
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.30");

		SettlementResult late = service.settle(commit(id, reserved.reservation().getRequestId(), "0.20"));

		assertThat(late.duplicate()).isFalse();
		assertThat(reservationRepository.findById(id).orElseThrow().getState()).isEqualTo(ReservationState.COMMITTED);
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0");
		assertThat(counter("prod").getCommittedUsd()).isEqualByComparingTo("0.20");
	}
}
