package com.tokenledgercloud.api.domain.reservation.dto;

import java.util.List;

/**
 * 예약 한 건과 그 정산 이력(적용 순서).
 */
public record ReservationDetailView(ReservationView reservation, List<SettlementView> settlements) {
}
