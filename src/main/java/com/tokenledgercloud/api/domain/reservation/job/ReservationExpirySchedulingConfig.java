package com.tokenledgercloud.api.domain.reservation.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 만료 예약 회수를 켰을 때만 스케줄링을 켠다. 끈 상태(기본)에서는 앱 전체에 스케줄러가 생기지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "token-pilot.reservation.expiry", name = "enabled", havingValue = "true")
public class ReservationExpirySchedulingConfig {
}
