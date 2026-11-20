package com.tokenledgercloud.api.domain.reservation.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
import com.tokenledgercloud.api.domain.reservation.service.ReservationExpiryService;

/** 회수 작업의 batch 반복 규칙. DB 없이 회수 서비스를 가짜로 바꿔서 확인한다. */
class ReservationExpiryJobTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 0, 0);

	private final ReservationExpiryService service = mock(ReservationExpiryService.class);

	@Test
	void stopsWhenABatchIsNotFull() {
		// 첫 batch는 꽉 차서(2건) 한 번 더 돌고, 두 번째 batch가 1건이라 멈춘다.
		when(service.expire(any(), eq(2)))
				.thenReturn(new ExpiryResult(1, 1, 0))
				.thenReturn(new ExpiryResult(0, 1, 0));

		ExpiryResult total = new ReservationExpiryJob(service, 2, 10).runOnce(NOW);

		assertThat(total).isEqualTo(new ExpiryResult(1, 2, 0));
		verify(service, times(2)).expire(NOW, 2);
	}

	@Test
	void skippedReservationsCountTowardsAFullBatchButMaxBatchesCapsTheRun() {
		// 다른 서버가 계속 먼저 처리해 건너뛴 예약만 나와도 batch는 꽉 찬 것으로 본다. 그래도 max-batches에서 멈춘다.
		when(service.expire(any(), eq(2))).thenReturn(new ExpiryResult(0, 0, 2));

		ExpiryResult total = new ReservationExpiryJob(service, 2, 3).runOnce(NOW);

		assertThat(total.skipped()).isEqualTo(6);
		verify(service, times(3)).expire(NOW, 2);
	}

	@Test
	void invalidSettingsAreRejectedAtStartup() {
		assertThatThrownBy(() -> new ReservationExpiryJob(service, 0, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReservationExpiryJob(service, 100, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
