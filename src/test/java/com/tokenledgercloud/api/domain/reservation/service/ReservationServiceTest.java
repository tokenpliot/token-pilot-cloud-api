package com.tokenledgercloud.api.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.decision.ReservationState;
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
 * 예약·정산 상태 머신의 규칙을 하나씩 확인한다. H2 + Hibernate 스키마 생성을 쓰고 Flyway는 끈다.
 * 서비스가 독립 트랜잭션으로 커밋하므로 테스트에 @Transactional을 붙이지 않고, 테스트가 끝나면 표를 비운다.
 */
// 설정을 따로 주지 않아 다른 기본 @SpringBootTest(IngestionApiKeyFailureIntegrationTest 등)와 같은 캐시 컨텍스트를 쓴다.
// 테스트마다 다른 설정을 주면 Spring 컨텍스트가 하나씩 더 캐시되어 전체 테스트가 메모리 부족으로 실패한다.
// 기본 테스트 설정은 Flyway를 끄고(H2 + 엔티티 기준 스키마), 이 테스트는 예약 표만 쓰고 비운다.
@SpringBootTest
class ReservationServiceTest {

	static final String PROJECT = "project-1";
	static final String ENV = "prod";
	static final String PERIOD = "2026-11";
	static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 11, 10, 12, 5);
	static final Instant OCCURRED = Instant.parse("2026-11-10T12:00:06Z");

	@Autowired ReservationService service;
	@Autowired BudgetReservationRepository reservationRepository;
	@Autowired BudgetReservationCounterRepository counterRepository;
	@Autowired BudgetReservationSettlementRepository settlementRepository;

	@AfterEach
	void cleanTables() {
		// 정산 기록 → 예약 → 카운터 순서로 지운다. (참조 방향)
		settlementRepository.deleteAll();
		reservationRepository.deleteAll();
		counterRepository.deleteAll();
	}

	// 예약 요청 도우미. 환경 예산만 걸고(프로젝트 예산 없음) 금액만 바꿔서 쓴다. SDK처럼 키는 dec:<requestId>.
	static ReservationCommand reserve(String requestId, String cost, String environmentLimit) {
		return reserve(ENV, requestId, 1000L, cost, null, environmentLimit);
	}

	static ReservationCommand reserve(String environment, String requestId, long promptTokens, String cost,
			String projectLimit, String environmentLimit) {
		return new ReservationCommand("org-1", PROJECT, environment, PERIOD, "dec:" + requestId, requestId,
				"openai", "gpt-test", promptTokens, 500L, new BigDecimal(cost),
				projectLimit == null ? null : new BigDecimal(projectLimit),
				environmentLimit == null ? null : new BigDecimal(environmentLimit),
				null, EXPIRES);
	}

	// 정산 요청 도우미. SDK처럼 키는 stl:<reservationId>:<result>.
	static SettlementCommand settle(String reservationId, String requestId, SettlementAction action, String totalCost) {
		return settle(reservationId, requestId, action, totalCost, OCCURRED);
	}

	static SettlementCommand settle(String reservationId, String requestId, SettlementAction action, String totalCost,
			Instant occurredAt) {
		boolean commit = action == SettlementAction.COMMIT;
		return new SettlementCommand(reservationId, PROJECT, "stl:" + reservationId + ":" + action, requestId, action,
				commit ? new Usage(900L, 400L, null, null) : null,
				commit ? Cost.total(new BigDecimal(totalCost)) : null,
				occurredAt);
	}

	private BudgetReservationCounter counter(String environmentKey) {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, environmentKey, PERIOD)
				.orElseThrow();
	}

	// 환경 카운터와 프로젝트 카운터는 같은 예약 금액을 함께 들고 있어야 한다.
	private void assertCounters(String held, String committed) {
		for (String key : new String[] {ENV, ""}) {
			BudgetReservationCounter counter = counter(key);
			assertThat(counter.getHeldUsd()).as("held " + key).isEqualByComparingTo(held);
			assertThat(counter.getCommittedUsd()).as("committed " + key).isEqualByComparingTo(committed);
		}
	}

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	@Test
	void reserveHoldsTheExpectedCostOnBothScopes() {
		ReservationResult result = service.reserve(reserve("req-1", "0.017500", "1"));

		assertThat(result.granted()).isTrue();
		assertThat(result.duplicate()).isFalse();
		assertThat(result.reservation().getState()).isEqualTo(ReservationState.RESERVED);
		assertCounters("0.0175", "0");
	}

	@Test
	void sameKeyAndSameRequestReturnsTheExistingReservationWithoutHoldingTwice() {
		ReservationResult first = service.reserve(reserve("req-1", "0.10", "1"));
		ReservationResult second = service.reserve(reserve("req-1", "0.10", "1"));

		assertThat(second.duplicate()).isTrue();
		assertThat(second.reservation().getId()).isEqualTo(first.reservation().getId());
		assertThat(reservationRepository.count()).isEqualTo(1);
		assertCounters("0.10", "0");
	}

	@Test
	void sameKeyWithDifferentBodyIsAnIdempotencyConflict() {
		service.reserve(reserve(ENV, "req-1", 1000L, "0.10", null, "1"));

		// 토큰이 다른 본문
		assertError(() -> service.reserve(reserve(ENV, "req-1", 2000L, "0.10", null, "1")), ErrorCode.IDEMPOTENCY_CONFLICT);
		// 키의 범위는 (프로젝트, 엔드포인트)라서, 같은 키로 다른 환경을 보내도 같은 키다.
		assertError(() -> service.reserve(reserve("dev", "req-1", 1000L, "0.10", null, "1")), ErrorCode.IDEMPOTENCY_CONFLICT);
		assertCounters("0.10", "0");
	}

	@Test
	void reservationOverTheLimitIsNotGrantedAndHoldsNothing() {
		service.reserve(reserve("req-1", "0.70", "1"));

		ReservationResult denied = service.reserve(reserve("req-2", "0.40", "1"));

		assertThat(denied.granted()).isFalse();
		assertThat(denied.reservation()).isNull();
		assertThat(reservationRepository.count()).isEqualTo(1);
		assertCounters("0.70", "0");
		// 딱 한도까지는 허용한다.
		assertThat(service.reserve(reserve("req-3", "0.30", "1")).granted()).isTrue();
		assertCounters("1.00", "0");
	}

	@Test
	void projectBudgetIsSharedAcrossEnvironments() {
		// 프로젝트 예산 1.00, 환경 예산은 각각 넉넉하다. prod 0.70 다음 dev 0.40은 프로젝트 예산을 넘는다.
		assertThat(service.reserve(reserve("prod", "req-1", 1000L, "0.70", "1", "5")).granted()).isTrue();
		assertThat(service.reserve(reserve("dev", "req-2", 1000L, "0.40", "1", "5")).granted()).isFalse();
		assertThat(service.reserve(reserve("dev", "req-3", 1000L, "0.30", "1", "5")).granted()).isTrue();

		assertThat(counter("").getHeldUsd()).isEqualByComparingTo("1.00");
		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.70");
		assertThat(counter("dev").getHeldUsd()).isEqualByComparingTo("0.30");
	}

	@Test
	void environmentBudgetAppliesEvenWhenTheProjectBudgetIsLarger() {
		assertThat(service.reserve(reserve(ENV, "req-1", 1000L, "0.70", "10", "1")).granted()).isTrue();
		assertThat(service.reserve(reserve(ENV, "req-2", 1000L, "0.40", "10", "1")).granted()).isFalse();
		assertCounters("0.70", "0");
	}

	@Test
	void environmentKeyIsCaseSensitive() {
		// "Prod"는 "prod"와 다른 범위다. 대소문자로 환경 예산을 피해 가거나 합쳐지지 않는다.
		service.reserve(reserve("prod", "req-1", 1000L, "0.70", null, "1"));
		service.reserve(reserve("Prod", "req-2", 1000L, "0.70", null, "1"));

		assertThat(counter("prod").getHeldUsd()).isEqualByComparingTo("0.70");
		assertThat(counter("Prod").getHeldUsd()).isEqualByComparingTo("0.70");
	}

	@Test
	void commitReleasesTheHoldAndCommitsTheTotalCost() {
		String id = service.reserve(reserve("req-1", "0.017500", "1")).reservation().getId();

		SettlementResult result = service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.014575"));

		assertThat(result.requestId()).isEqualTo("req-1");
		assertThat(result.previousState()).isEqualTo(ReservationState.RESERVED);
		assertThat(result.state()).isEqualTo(ReservationState.COMMITTED);
		assertThat(result.committedCostUsd()).isEqualByComparingTo("0.014575");
		assertThat(result.releasedCostUsd()).isEqualByComparingTo("0.002925");
		assertThat(result.duplicate()).isFalse();
		assertCounters("0", "0.014575");
	}

	@Test
	void commitUsesTotalCostEvenIfTheSplitDoesNotAddUp() {
		String id = service.reserve(reserve("req-1", "0.02", "1")).reservation().getId();
		// SDK 분할(prompt + completion)이 서버 계산과 달라도 거부하지 않고 totalCostUsd를 쓴다.
		SettlementCommand command = new SettlementCommand(id, PROJECT, "stl:" + id + ":COMMIT", "req-1",
				SettlementAction.COMMIT, new Usage(900L, 400L, null, null),
				new Cost(new BigDecimal("0.001"), new BigDecimal("0.002"), null, null, new BigDecimal("0.015")),
				OCCURRED);

		assertThat(service.settle(command).committedCostUsd()).isEqualByComparingTo("0.015");
		assertCounters("0", "0.015");
	}

	@Test
	void sameKeyAndSameBodyReturnsTheOriginalResultAndDoesNotCountTwice() {
		String id = service.reserve(reserve("req-1", "0.02", "1")).reservation().getId();
		SettlementResult first = service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.015"));

		// 금액 표기만 다른 같은 본문("0.015" = "0.015000")은 재전송이다.
		SettlementResult again = service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.015000"));

		assertThat(again.duplicate()).isTrue();
		assertThat(again.previousState()).isEqualTo(first.previousState());
		assertThat(again.committedCostUsd()).isEqualByComparingTo(first.committedCostUsd());
		assertThat(settlementRepository.count()).isEqualTo(1);
		assertCounters("0", "0.015");
	}

	@Test
	void sameKeyWithOnlyADifferentOccurredAtIsAnIdempotencyConflict() {
		String id = service.reserve(reserve("req-1", "0.02", "1")).reservation().getId();
		service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.015"));

		// 결과가 아니라 본문을 비교한다. occurredAt만 달라도 같은 키의 다른 본문이다.
		assertError(() -> service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.015",
				OCCURRED.plusSeconds(1))), ErrorCode.IDEMPOTENCY_CONFLICT);
		assertCounters("0", "0.015");
	}

	@Test
	void differentSettlementOnATerminalReservationIsAConflict() {
		String id = service.reserve(reserve("req-1", "0.02", "1")).reservation().getId();
		service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.015"));

		// 새 키(stl:<id>:RELEASE)로 종료 상태와 다른 결과를 요구한다.
		assertError(() -> service.settle(settle(id, "req-1", SettlementAction.RELEASE, null)), ErrorCode.CONFLICT);
		assertCounters("0", "0.015");
	}

	@Test
	void releaseGivesTheBudgetBack() {
		String id = service.reserve(reserve("req-1", "0.80", "1")).reservation().getId();
		assertThat(service.reserve(reserve("req-2", "0.50", "1")).granted()).isFalse();

		SettlementResult released = service.settle(settle(id, "req-1", SettlementAction.RELEASE, null));

		assertThat(released.state()).isEqualTo(ReservationState.RELEASED);
		assertThat(released.releasedCostUsd()).isEqualByComparingTo("0.80");
		assertThat(released.committedCostUsd()).isNull();
		assertCounters("0", "0");
		assertThat(service.reserve(reserve("req-2", "0.50", "1")).granted()).isTrue();
	}

	@Test
	void unknownResultKeepsTheLiabilityUntilTheLateActualUsageArrives() {
		String id = service.reserve(reserve("req-1", "0.50", "1")).reservation().getId();

		// 타임아웃 등으로 결과를 모르면 0원으로 풀지 않고 예약 금액을 계속 잡아둔다.
		SettlementResult unknown = service.settle(settle(id, "req-1", SettlementAction.RECONCILIATION_REQUIRED, null));
		assertThat(unknown.state()).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertCounters("0.50", "0");
		assertThat(service.reserve(reserve("req-2", "0.60", "1")).granted()).isFalse();

		// 나중에 실제 사용량이 도착하면 그때 확정하고 남은 금액을 돌려준다.
		SettlementResult late = service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.45"));
		assertThat(late.previousState()).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertThat(late.state()).isEqualTo(ReservationState.COMMITTED);
		assertCounters("0", "0.45");

		// 앞서 보낸 미확인 정산의 재전송은 그때 결과(RECONCILIATION_REQUIRED)를 그대로 받는다.
		SettlementResult retried = service.settle(settle(id, "req-1", SettlementAction.RECONCILIATION_REQUIRED, null));
		assertThat(retried.duplicate()).isTrue();
		assertThat(retried.state()).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertCounters("0", "0.45");
	}

	@Test
	void writeOffCommitsTheReservedAmountInsteadOfZero() {
		String id = service.reserve(reserve("req-1", "0.50", "1")).reservation().getId();
		service.settle(settle(id, "req-1", SettlementAction.RECONCILIATION_REQUIRED, null));

		SettlementResult writtenOff = service.settle(settle(id, "req-1", SettlementAction.WRITE_OFF, null));

		assertThat(writtenOff.state()).isEqualTo(ReservationState.WRITTEN_OFF);
		assertThat(writtenOff.committedCostUsd()).isEqualByComparingTo("0.50");
		assertCounters("0", "0.50");
	}

	@Test
	void reservationOfAnotherProjectIsNotFound() {
		String id = service.reserve(reserve("req-1", "0.10", "1")).reservation().getId();
		SettlementCommand foreign = new SettlementCommand(id, "project-2", "stl:" + id + ":RELEASE", "req-1",
				SettlementAction.RELEASE, null, null, OCCURRED);

		assertError(() -> service.settle(foreign), ErrorCode.NOT_FOUND);
		assertCounters("0.10", "0");
	}

	@Test
	void settlementKeyReusedForAnotherReservationIsAnIdempotencyConflict() {
		String first = service.reserve(reserve("req-1", "0.10", "1")).reservation().getId();
		String second = service.reserve(reserve("req-2", "0.10", "1")).reservation().getId();
		service.settle(settle(first, "req-1", SettlementAction.RELEASE, null));

		SettlementCommand reused = new SettlementCommand(second, PROJECT, "stl:" + first + ":RELEASE", "req-2",
				SettlementAction.RELEASE, null, null, OCCURRED);

		assertError(() -> service.settle(reused), ErrorCode.IDEMPOTENCY_CONFLICT);
		assertThat(counter(ENV).getHeldUsd()).isEqualByComparingTo("0.10");
	}

	@Test
	void settlementWithAnotherRequestIdIsRejected() {
		String id = service.reserve(reserve("req-1", "0.10", "1")).reservation().getId();

		assertError(() -> service.settle(settle(id, "req-other", SettlementAction.RELEASE, null)), ErrorCode.INVALID_INPUT);
	}

	@Test
	void markInFlightIsIdempotentAndOnlyFromReserved() {
		String id = service.reserve(reserve("req-1", "0.10", "1")).reservation().getId();

		assertThat(service.markInFlight(id, PROJECT).getState()).isEqualTo(ReservationState.IN_FLIGHT);
		assertThat(service.markInFlight(id, PROJECT).getState()).isEqualTo(ReservationState.IN_FLIGHT);

		SettlementResult result = service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.08"));
		assertThat(result.previousState()).isEqualTo(ReservationState.IN_FLIGHT);
		assertError(() -> service.markInFlight(id, PROJECT), ErrorCode.CONFLICT);
	}

	@Test
	void invalidInputIsRejectedBeforeTouchingTheDatabase() {
		// 소수점 7자리는 반올림하지 않고 거절한다.
		assertThatThrownBy(() -> reserve("req-1", "0.0000001", "1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reserve("req-1", "0", "1")).isInstanceOf(IllegalArgumentException.class);
		// Idempotency-Key는 8~128자.
		assertThatThrownBy(() -> new ReservationCommand("org-1", PROJECT, ENV, PERIOD, "short", "req-1", "openai",
				"gpt-test", null, null, BigDecimal.ONE, null, null, null, EXPIRES))
				.isInstanceOf(IllegalArgumentException.class);
		// COMMIT에는 사용량과 비용이 필요하고, RELEASE에는 비용이 오면 안 된다.
		assertThatThrownBy(() -> new SettlementCommand("r", PROJECT, "stl:r:COMMIT", "req-1", SettlementAction.COMMIT,
				null, null, OCCURRED)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SettlementCommand("r", PROJECT, "stl:r:RELEASE", "req-1", SettlementAction.RELEASE,
				null, Cost.total(BigDecimal.ONE), OCCURRED)).isInstanceOf(IllegalArgumentException.class);
		assertThat(counterRepository.count()).isZero();
	}
}
