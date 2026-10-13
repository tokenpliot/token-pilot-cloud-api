package com.tokenledgercloud.api.domain.accounting.observe;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.accounting.observe.dto.IngestionLagResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.UnattributedCostResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.UnpricedSummaryResponse;
import com.tokenledgercloud.api.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * Observe 원장 조회 API. 인증이 필요하다. (SecurityConfig의 /api/observe/** 규칙)
 */
// JSON을 돌려주는 컨트롤러로 등록한다.
@RestController
// 이 컨트롤러의 공통 경로.
@RequestMapping("/api/observe/ledger")
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ObserveLedgerController {

    // 현재는 조직이 하나뿐이라 다른 서비스와 같은 기본 조직 id를 쓴다. TenantContext가 생기면 그 값으로 교체한다.
    private static final String DEFAULT_ORGANIZATION_ID = "default-org";

    // 조회 서비스.
    private final ObserveLedgerQueryService queryService;

    // GET /api/observe/ledger/ingestion-lag?projectId=...&environment=...&period=today
    @GetMapping("/ingestion-lag")
    public ResponseEntity<ApiResponse<IngestionLagResponse>> ingestionLag(
            @RequestParam String projectId,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "today") String period) {
        // 기간 이름을 시각 범위로 바꿔서 서비스에 넘긴다.
        LedgerPeriod range = LedgerPeriod.resolve(period, LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("수집 지연 조회 성공",
                queryService.getIngestionLag(DEFAULT_ORGANIZATION_ID, projectId, environment, range, LocalDateTime.now())));
    }

    // GET /api/observe/ledger/unattributed-cost?projectId=...&environment=...&period=month
    @GetMapping("/unattributed-cost")
    public ResponseEntity<ApiResponse<UnattributedCostResponse>> unattributedCost(
            @RequestParam String projectId,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "month") String period) {
        LedgerPeriod range = LedgerPeriod.resolve(period, LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("미귀속 비용 조회 성공",
                queryService.getUnattributedCost(DEFAULT_ORGANIZATION_ID, projectId, environment, range)));
    }

    // GET /api/observe/ledger/unpriced?projectId=...&environment=...&period=month
    @GetMapping("/unpriced")
    public ResponseEntity<ApiResponse<UnpricedSummaryResponse>> unpriced(
            @RequestParam String projectId,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "month") String period) {
        LedgerPeriod range = LedgerPeriod.resolve(period, LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("가격 누락 조회 성공",
                queryService.getUnpriced(DEFAULT_ORGANIZATION_ID, projectId, environment, range)));
    }
}
