package com.tokenledgercloud.api.domain.reservation.job;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tokenledgercloud.api.domain.reservation.ExpiryResult;
import com.tokenledgercloud.api.domain.reservation.service.ReservationExpiryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 만료 예약 회수를 주기적으로 실행한다(이슈 #17). 기본은 꺼져 있고
 * {@code token-pilot.reservation.expiry.enabled=true}일 때만 등록된다.
 *
 * <p>재시작 복구: 서버가 꺼져 있던 동안 만료된 예약은 첫 주기를 기다리지 않고 기동 직후 바로 회수한다.
 * 예약·정산 상태는 모두 DB에 있으므로, 메모리에서 복구할 것은 없고 이 회수만 하면 된다.
 *
 * <p>한 번 실행할 때 batch-size만큼씩 최대 max-batches번 회수한다. 밀린 예약이 많아도 한 번에 너무 오래 잡고 있지 않게 하고,
 * 남은 것은 다음 주기에 이어서 처리한다. 여러 서버에서 동시에 돌아도 회수 정산은 예약마다 한 번만 적용된다.
 */
// 설정으로 켠 경우에만 이 작업을 등록한다.
@ConditionalOnProperty(prefix = "token-pilot.reservation.expiry", name = "enabled", havingValue = "true")
// 스프링 빈으로 등록한다.
@Component
// 실행 결과를 로그로 남긴다.
@Slf4j
public class ReservationExpiryJob {

	private final ReservationExpiryService expiryService;
	// 한 번에 회수할 예약 수.
	private final int batchSize;
	// 한 번 실행에서 반복할 최대 횟수. (batchSize × maxBatches가 한 주기의 상한)
	private final int maxBatches;

	public ReservationExpiryJob(ReservationExpiryService expiryService,
			@Value("${token-pilot.reservation.expiry.batch-size:100}") int batchSize,
			@Value("${token-pilot.reservation.expiry.max-batches:10}") int maxBatches) {
		if (batchSize <= 0 || maxBatches <= 0) {
			throw new IllegalArgumentException("token-pilot.reservation.expiry.batch-size and max-batches must be positive");
		}
		this.expiryService = expiryService;
		this.batchSize = batchSize;
		this.maxBatches = maxBatches;
	}

	// 재시작 복구. 앱이 요청을 받을 준비가 끝난 직후 한 번 회수한다.
	@EventListener(ApplicationReadyEvent.class)
	public void recoverOnStartup() {
		ExpiryResult result = runOnce(LocalDateTime.now(ZoneOffset.UTC));
		log.info("Startup reservation recovery: released={}, reconciliationRequired={}, skipped={}",
				result.released(), result.reconciliationRequired(), result.skipped());
	}

	// 주기 실행. 이전 실행이 끝난 뒤 interval만큼 쉬고 다시 돈다. (기본 1분)
	@Scheduled(fixedDelayString = "${token-pilot.reservation.expiry.interval:PT1M}",
			initialDelayString = "${token-pilot.reservation.expiry.interval:PT1M}")
	public void sweep() {
		ExpiryResult result = runOnce(LocalDateTime.now(ZoneOffset.UTC));
		if (result.processed() > 0 || result.skipped() > 0) {
			log.info("Expired reservations: released={}, reconciliationRequired={}, skipped={}",
					result.released(), result.reconciliationRequired(), result.skipped());
		}
	}

	/**
	 * nowUtc 기준으로 만료 예약을 batch 단위로 회수하고 합계를 돌려준다.
	 * 한 batch가 꽉 차지 않으면 더 남은 것이 없다는 뜻이라 멈춘다.
	 */
	public ExpiryResult runOnce(LocalDateTime nowUtc) {
		int released = 0;
		int reconciliationRequired = 0;
		int skipped = 0;
		for (int batch = 0; batch < maxBatches; batch++) {
			ExpiryResult result = expiryService.expire(nowUtc, batchSize);
			released += result.released();
			reconciliationRequired += result.reconciliationRequired();
			skipped += result.skipped();
			if (result.processed() + result.skipped() < batchSize) {
				break;
			}
		}
		return new ExpiryResult(released, reconciliationRequired, skipped);
	}
}
