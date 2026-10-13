package com.tokenledgercloud.api.domain.accounting.observe;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

/**
 * 조회 기간. from은 포함, to는 미포함이다. (from <= 시각 < to)
 */
public record LedgerPeriod(LocalDateTime from, LocalDateTime to) {

    /**
     * 기간 이름을 실제 시각 범위로 바꾼다.
     * today: 오늘 하루, week: 오늘을 포함한 최근 7일, month: 이번 달 1일부터 다음 달 1일 전까지.
     *
     * @throws ApiException 지원하지 않는 기간 이름이면 INVALID_PERIOD
     */
    public static LedgerPeriod resolve(String period, LocalDate today) {
        // 기간 이름이 없으면 잘못된 요청이다.
        if (period == null) {
            throw new ApiException(ErrorCode.INVALID_PERIOD);
        }
        // 이름에 맞는 범위를 계산한다.
        return switch (period) {
            // 오늘 00:00부터 내일 00:00 전까지
            case "today" -> new LedgerPeriod(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
            // 6일 전 00:00부터 내일 00:00 전까지 (오늘 포함 7일)
            case "week" -> new LedgerPeriod(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
            // 이번 달 1일 00:00부터 다음 달 1일 00:00 전까지
            case "month" -> new LedgerPeriod(
                    today.withDayOfMonth(1).atStartOfDay(),
                    today.withDayOfMonth(1).plusMonths(1).atStartOfDay());
            // 그 외 이름은 거부한다.
            default -> throw new ApiException(ErrorCode.INVALID_PERIOD);
        };
    }
}
