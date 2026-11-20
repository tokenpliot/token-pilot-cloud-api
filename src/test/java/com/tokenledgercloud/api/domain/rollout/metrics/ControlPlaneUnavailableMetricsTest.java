package com.tokenledgercloud.api.domain.rollout.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.tokenledgercloud.api.domain.decision.ControlPlaneExceptionHandler;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 판정 API의 중앙 장애(503)가 사유별로 세지고, 입력 오류·인증 오류는 중앙 장애로 세지지 않는지 확인한다. (이슈 #19)
 * 스프링 없이 처리기를 직접 부르고, 전역 레지스트리에 이 테스트 전용 레지스트리를 잠시 붙인다.
 */
class ControlPlaneUnavailableMetricsTest {

	private final ControlPlaneExceptionHandler handler = new ControlPlaneExceptionHandler();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@BeforeEach
	void attach() {
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void detach() {
		Metrics.removeRegistry(registry);
		registry.close();
	}

	private double unavailable(String reason) {
		var counter = registry.find(RolloutMetrics.CONTROL_PLANE_UNAVAILABLE).tag("reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void storeFailureAndUnresolvedConflictAreCountedByReason() {
		assertThat(handler.handleStoreFailure(new DataAccessResourceFailureException("db down")).getStatusCode().value())
				.isEqualTo(503);
		assertThat(handler.handleApi(new ApiException(ErrorCode.INGESTION_RETRY_LATER)).getStatusCode().value())
				.isEqualTo(503);

		assertThat(unavailable(RolloutMetrics.REASON_STORE_DOWN)).isEqualTo(1);
		assertThat(unavailable(RolloutMetrics.REASON_RETRY_LATER)).isEqualTo(1);
	}

	@Test
	void clientErrorsAreNotCentralFailures() {
		handler.handleApi(new ApiException(ErrorCode.INVALID_INPUT));
		handler.handleApi(new ApiException(ErrorCode.UNAUTHORIZED));

		// 전역 레지스트리에 붙이면 이미 있던 카운터가 0으로 따라오므로, 개수가 아니라 값으로 확인한다.
		assertThat(registry.find(RolloutMetrics.CONTROL_PLANE_UNAVAILABLE).counters())
				.allSatisfy(counter -> assertThat(counter.count()).isZero());
	}
}
