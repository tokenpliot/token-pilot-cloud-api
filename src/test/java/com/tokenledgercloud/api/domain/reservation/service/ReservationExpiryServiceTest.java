package com.tokenledgercloud.api.domain.reservation.service;

import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.ENV;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.EXPIRES;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.PERIOD;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.PROJECT;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.reserve;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.settle;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.entity.BudgetReservationCounter;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;

/**
 * 만료 예약 회수 규칙을 확인한다. 모든 예약은 ReservationServiceTest.EXPIRES(2026-11-10 12:05 UTC)에 만료된다.
 */
// 설정을 따로 주지 않아 다른 기본 @SpringBootTest와 같은 캐시 컨텍스트를 쓴다. (컨텍스트가 늘면 전체 테스트가 메모리 부족)
@SpringBootTest
class ReservationExpiryServiceTest {

	private static final LocalDateTime AFTER = EXPIRES.plusMinutes(1);
	private static final LocalDateTime BEFORE = EXPIRES.minusMinutes(1);

	@Autowired ReservationExpiryService expiry;
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

	private ReservationState stateOf(String id) {
		return reservationRepository.findById(id).orElseThrow().getState();
	}

	private BudgetReservationCounter counter() {
		return counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, ENV, PERIOD).orElseThrow();
	}

	@Test
	void expiredInFlightReservationKeepsItsLiability() {
		String id = service.reserve(reserve("req-1", "0.40", "1")).reservation().getId();
		service.markInFlight(id, PROJECT);

		ExpiryResult result = expiry.expire(AFTER, 100);

		// 호출은 시작됐는데 결과를 모른다: 0원으로 풀지 않고 미확인으로 남긴다.
		assertThat(result.reconciliationRequired()).isEqualTo(1);
		assertThat(stateOf(id)).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertThat(counter().getHeldUsd()).isEqualByComparingTo("0.40");
	}

	@Test
	void expiredReservedReservationIsReconciliationRequiredByDefaultAndLateUsageStillSettles() {
		String id = service.reserve(reserve("req-1", "0.40", "1")).reservation().getId();

		assertThat(expiry.expire(AFTER, 100).reconciliationRequired()).isEqualTo(1);
		assertThat(stateOf(id)).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertThat(counter().getHeldUsd()).isEqualByComparingTo("0.40");

		// 회수 뒤에 실제 사용량이 늦게 와도 정상 정산된다.
		service.settle(settle(id, "req-1", SettlementAction.COMMIT, "0.30"));
		assertThat(stateOf(id)).isEqualTo(ReservationState.COMMITTED);
		assertThat(counter().getHeldUsd()).isEqualByComparingTo("0");
		assertThat(counter().getCommittedUsd()).isEqualByComparingTo("0.30");
	}

	@Test
	void expiredReservedReservationCanBeReleasedAsTheAdrSays() {
		String id = service.reserve(reserve("req-1", "0.40", "1")).reservation().getId();

		ExpiryResult result = expiry.expire(AFTER, 100, SettlementAction.RELEASE);

		assertThat(result.released()).isEqualTo(1);
		assertThat(stateOf(id)).isEqualTo(ReservationState.RELEASED);
		assertThat(counter().getHeldUsd()).isEqualByComparingTo("0");
	}

	@Test
	void reservationsThatAreNotExpiredOrAlreadySettledAreLeftAlone() {
		String open = service.reserve(reserve("req-1", "0.10", "1")).reservation().getId();
		String settled = service.reserve(reserve("req-2", "0.10", "1")).reservation().getId();
		service.settle(settle(settled, "req-2", SettlementAction.COMMIT, "0.05"));

		// 아직 만료 전이면 아무것도 바꾸지 않는다.
		assertThat(expiry.expire(BEFORE, 100).processed()).isZero();
		assertThat(stateOf(open)).isEqualTo(ReservationState.RESERVED);

		// 만료 뒤에는 열린 예약만 닫고, 이미 정산된 예약은 대상이 아니다.
		assertThat(expiry.expire(AFTER, 100).processed()).isEqualTo(1);
		assertThat(stateOf(settled)).isEqualTo(ReservationState.COMMITTED);
	}

	@Test
	void limitCapsOneRunAndRunningAgainIsHarmless() {
		for (int i = 1; i <= 3; i++) {
			service.reserve(reserve("req-" + i, "0.10", "1"));
		}

		assertThat(expiry.expire(AFTER, 2).processed()).isEqualTo(2);
		assertThat(expiry.expire(AFTER, 2).processed()).isEqualTo(1);
		// 세 번째 실행은 할 일이 없다. 카운터도 그대로다. (부채는 계속 잡혀 있다)
		assertThat(expiry.expire(AFTER, 2).processed()).isZero();
		assertThat(counter().getHeldUsd()).isEqualByComparingTo("0.30");
		assertThat(settlementRepository.count()).isEqualTo(3);
	}
}
