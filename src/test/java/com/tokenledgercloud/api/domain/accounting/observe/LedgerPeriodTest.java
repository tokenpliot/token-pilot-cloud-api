package com.tokenledgercloud.api.domain.accounting.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.tokenledgercloud.api.global.exception.ApiException;

/** 기간 이름이 올바른 시각 범위로 바뀌는지 확인하는 단위 테스트. */
class LedgerPeriodTest {

    // 기준 날짜는 2026-10-14(수요일)로 고정한다.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void todayCoversOnlyThatDay() {
        var period = LedgerPeriod.resolve("today", TODAY);
        // 오늘 00:00 이상, 내일 00:00 미만이어야 한다.
        assertThat(period.from()).isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0));
        assertThat(period.to()).isEqualTo(LocalDateTime.of(2026, 10, 15, 0, 0));
    }

    @Test
    void weekCoversSevenDaysIncludingToday() {
        var period = LedgerPeriod.resolve("week", TODAY);
        // 6일 전부터 내일 00:00 전까지, 즉 오늘 포함 7일이어야 한다.
        assertThat(period.from()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
        assertThat(period.to()).isEqualTo(LocalDateTime.of(2026, 10, 15, 0, 0));
    }

    @Test
    void monthCoversWholeCalendarMonth() {
        var period = LedgerPeriod.resolve("month", TODAY);
        // 10월 1일부터 11월 1일 전까지여야 한다.
        assertThat(period.from()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(period.to()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    @Test
    void unknownOrMissingPeriodIsRejected() {
        // 지원하지 않는 이름과 null은 ApiException(INVALID_PERIOD)이어야 한다.
        assertThatThrownBy(() -> LedgerPeriod.resolve("year", TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LedgerPeriod.resolve(null, TODAY)).isInstanceOf(ApiException.class);
    }
}
