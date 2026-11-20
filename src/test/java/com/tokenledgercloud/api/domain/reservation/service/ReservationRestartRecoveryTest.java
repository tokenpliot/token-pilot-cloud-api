package com.tokenledgercloud.api.domain.reservation.service;

import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.ENV;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.PERIOD;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.PROJECT;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.reserve;
import static com.tokenledgercloud.api.domain.reservation.service.ReservationServiceTest.settle;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import com.tokenledgercloud.api.TokenLedgerCloudApplication;
import com.tokenledgercloud.api.domain.decision.ReservationState;
import com.tokenledgercloud.api.domain.reservation.ReservationCommand;
import com.tokenledgercloud.api.domain.reservation.SettlementAction;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationCounterRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationRepository;
import com.tokenledgercloud.api.domain.reservation.repository.BudgetReservationSettlementRepository;

/**
 * 재시작 복구(이슈 #17 완료 기준 "크래시/재시작 테스트").
 * 클라이언트가 예약만 하고 죽어서 정산이 오지 않은 상태로 서버가 재시작되는 상황을 만든다.
 * 같은 DB로 두 번째 애플리케이션을 띄우면(메모리에서 넘어가는 것 없음) 기동 직후 만료 예약이 회수되어야 한다.
 */
// 설정을 따로 주지 않아 다른 기본 @SpringBootTest와 같은 캐시 컨텍스트를 쓴다. (컨텍스트가 늘면 전체 테스트가 메모리 부족)
@SpringBootTest
class ReservationRestartRecoveryTest {

	@Autowired ReservationService service;
	@Autowired BudgetReservationRepository reservationRepository;
	@Autowired BudgetReservationCounterRepository counterRepository;
	@Autowired BudgetReservationSettlementRepository settlementRepository;
	@Autowired DataSource dataSource;

	@AfterEach
	void cleanTables() {
		settlementRepository.deleteAll();
		reservationRepository.deleteAll();
		counterRepository.deleteAll();
	}

	private ReservationState stateOf(String id) {
		return reservationRepository.findById(id).orElseThrow().getState();
	}

	@Test
	void restartedServerClosesReservationsThatExpiredWhileItWasDown() throws Exception {
		// 이미 만료된 예약 두 개(클라이언트가 정산 없이 죽음), 아직 유효한 예약 하나, 이미 정산된 예약 하나.
		String abandoned = service.reserve(reserve("req-1", "0.20", "5")).reservation().getId();
		String inFlight = service.reserve(reserve("req-2", "0.30", "5")).reservation().getId();
		service.markInFlight(inFlight, PROJECT);
		String stillValid = service.reserve(new ReservationCommand("org-1", PROJECT, ENV, PERIOD, "dec:req-3", "req-3",
				"openai", "gpt-test", 1000L, 500L, new BigDecimal("0.10"), null, new BigDecimal("5"), null,
				LocalDateTime.now(ZoneOffset.UTC).plusDays(1))).reservation().getId();
		String settled = service.reserve(reserve("req-4", "0.10", "5")).reservation().getId();
		service.settle(settle(settled, "req-4", SettlementAction.COMMIT, "0.05"));

		// 같은 DB로 서버를 다시 띄운다. 회수 작업을 켜고, 주기 실행은 테스트 중에 돌지 않게 길게 둔다.
		try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(TokenLedgerCloudApplication.class)
				.properties(
						"spring.datasource.url=" + databaseUrl(),
						"spring.datasource.username=sa",
						"spring.datasource.password=",
						// 테이블을 지우거나 새로 만들지 않는다. 재시작한 서버는 기존 데이터를 그대로 봐야 한다.
						"spring.jpa.hibernate.ddl-auto=none",
						"spring.jpa.show-sql=false",
						"spring.flyway.enabled=false",
						"server.port=0",
						"token-pilot.reservation.expiry.enabled=true",
						"token-pilot.reservation.expiry.interval=PT1H")
				.run()) {
			assertThat(restarted.isRunning()).isTrue();
		}

		// 기동 직후 회수: 만료된 예약은 0원으로 풀리지 않고 결과 미확인으로 남는다. (부채 유지)
		assertThat(stateOf(abandoned)).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		assertThat(stateOf(inFlight)).isEqualTo(ReservationState.RECONCILIATION_REQUIRED);
		// 아직 유효한 예약과 이미 정산된 예약은 건드리지 않는다.
		assertThat(stateOf(stillValid)).isEqualTo(ReservationState.RESERVED);
		assertThat(stateOf(settled)).isEqualTo(ReservationState.COMMITTED);
		// 카운터: 미확인 0.50 + 유효 0.10이 계속 잡혀 있고, 정산된 0.05만 비용이다.
		var counter = counterRepository.findByProjectIdAndEnvironmentKeyAndBudgetPeriod(PROJECT, ENV, PERIOD).orElseThrow();
		assertThat(counter.getHeldUsd()).isEqualByComparingTo("0.60");
		assertThat(counter.getCommittedUsd()).isEqualByComparingTo("0.05");

		// 재시작 뒤에 늦게 온 실제 사용량도 정상 정산된다.
		service.settle(settle(abandoned, "req-1", SettlementAction.COMMIT, "0.15"));
		assertThat(stateOf(abandoned)).isEqualTo(ReservationState.COMMITTED);
	}

	// 지금 테스트 컨텍스트가 쓰는 인메모리 DB 주소. 두 번째 애플리케이션이 같은 DB에 붙게 한다.
	private String databaseUrl() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			return connection.getMetaData().getURL();
		}
	}
}
