package com.tokenledgercloud.api.domain.accounting.observe;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.AccountingStatus;
import com.tokenledgercloud.api.domain.accounting.CallStatus;
import com.tokenledgercloud.api.domain.accounting.ReconciliationClassifier;
import com.tokenledgercloud.api.domain.accounting.ReconciliationState;
import com.tokenledgercloud.api.domain.accounting.SettlementState;
import com.tokenledgercloud.api.domain.accounting.observe.dto.IngestionLagResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.LedgerSpendSummaryResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.ReconciliationSummaryResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.UnattributedCostResponse;
import com.tokenledgercloud.api.domain.accounting.observe.dto.UnpricedSummaryResponse;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 이슈 #11의 조회 3종(수집 지연, 미귀속 비용, 가격 누락)을 만든다.
 * 모든 메서드는 조직 id를 명시적으로 받아서, 다른 조직의 프로젝트를 조회하면 NOT_FOUND가 되게 한다.
 */
// 스프링 서비스 빈으로 등록한다.
@Service
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ObserveLedgerQueryService {

    // 원장 조회 쿼리 저장소.
    private final ObserveLedgerQueryRepository queryRepository;
    // 프로젝트가 해당 조직 소유인지 확인하는 저장소.
    private final ProjectRepository projectRepository;
    // 기존 방식으로 기록된 이벤트의 합계를 읽는 저장소.
    private final UsageLogRepository usageLogRepository;
    // 새 원장의 확정 지출과 반영되지 않은 건수를 읽는 저장소.
    private final LedgerSpendRepository ledgerSpendRepository;

    /** 수집 지연: 마지막으로 받은 시각과 미정산 건수를 돌려준다. now는 지난 시간 계산 기준이다. */
    @Transactional(readOnly = true)
    public IngestionLagResponse getIngestionLag(String organizationId, String projectId, String environment,
                                                LedgerPeriod period, LocalDateTime now) {
        // 프로젝트가 이 조직 소유인지 먼저 확인한다.
        requireProject(organizationId, projectId);
        String env = blankToNull(environment);
        // 기간 안의 요약 한 줄을 읽는다.
        var row = queryRepository.findIngestionRow(organizationId, projectId, env, period.from(), period.to());
        // 이벤트가 하나도 없으면 집계 값이 null이므로 0으로 바꾼다.
        long eventCount = row.getEventCount() == null ? 0L : row.getEventCount();
        long unsettled = row.getUnsettledCount() == null ? 0L : row.getUnsettledCount();
        // 마지막 수신 후 지난 초를 계산한다. 이벤트가 없으면 계산할 수 없으므로 null이다.
        Long seconds = row.getLastReceivedAt() == null
                ? null
                : Math.max(0L, Duration.between(row.getLastReceivedAt(), now).getSeconds());
        return new IngestionLagResponse(projectId, env, period.from(), period.to(),
                eventCount, unsettled, row.getLastReceivedAt(), row.getLastOccurredAt(), seconds);
    }

    /** 미귀속 비용: 격리된 이벤트 수와, 그 이벤트의 최신 실제 확정 금액 합계를 돌려준다. */
    @Transactional(readOnly = true)
    public UnattributedCostResponse getUnattributedCost(String organizationId, String projectId, String environment,
                                                        LedgerPeriod period) {
        requireProject(organizationId, projectId);
        String env = blankToNull(environment);
        // 격리 상태 이벤트 수.
        long count = queryRepository.countByAccountingStatus(organizationId, projectId, env,
                period.from(), period.to(), AccountingStatus.QUARANTINED.name());
        // 격리 이벤트의 최신 ACTUAL/PRICED 금액 합계. 대상이 없으면 null이므로 0으로 바꾼다.
        BigDecimal amount = queryRepository.sumQuarantinedAmount(organizationId, projectId, env,
                period.from(), period.to(),
                AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED);
        return new UnattributedCostResponse(projectId, env, period.from(), period.to(),
                count, amount == null ? BigDecimal.ZERO : amount, "USD");
    }

    /** 가격 누락: 최신 실제 값이 UNPRICED인 이벤트를 공급자/모델별로 돌려준다. */
    @Transactional(readOnly = true)
    public UnpricedSummaryResponse getUnpriced(String organizationId, String projectId, String environment,
                                               LedgerPeriod period) {
        requireProject(organizationId, projectId);
        String env = blankToNull(environment);
        // 공급자/모델별 가격 누락 내역을 읽어서 응답 항목으로 바꾼다.
        List<UnpricedSummaryResponse.Item> items = queryRepository.findUnpriced(organizationId, projectId, env,
                        period.from(), period.to(),
                        AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.UNPRICED)
                .stream()
                .map(row -> new UnpricedSummaryResponse.Item(
                        row.getProvider(), row.getModel(),
                        row.getEventCount() == null ? 0L : row.getEventCount(),
                        row.getTotalTokens() == null ? 0L : row.getTotalTokens()))
                .toList();
        // 총 건수는 항목별 건수의 합이다.
        long total = items.stream().mapToLong(UnpricedSummaryResponse.Item::eventCount).sum();
        return new UnpricedSummaryResponse(projectId, env, period.from(), period.to(), total, items);
    }

    /**
     * 월 지출 요약: 기존 합계와 새 원장의 확정 금액을 따로 보여주고, 지출에 반영되지 않은 이벤트 수를 알려준다.
     * 기존(v0) 예산 응답은 그대로 두고 이 응답으로 원장 반영 숫자를 제공한다. (설계 문서 6장)
     *
     * @param month yyyy-MM 형식. 월 경계는 UTC 기준이다.
     */
    @Transactional(readOnly = true)
    public LedgerSpendSummaryResponse getSpendSummary(String organizationId, String projectId, String environment,
                                                      String month) {
        requireProject(organizationId, projectId);
        String env = blankToNull(environment);
        // 월 문자열을 해석한다. 형식이 틀리면 잘못된 기간으로 거절한다.
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new ApiException(ErrorCode.INVALID_PERIOD);
        }
        // 이번 달 1일 00:00 이상, 다음 달 1일 00:00 미만.
        LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime to = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        // 기존 합계와 새 원장의 확정 금액은 서로 겹치지 않는 행이라 더해도 이중 계산이 없다.
        BigDecimal legacy = usageLogRepository.sumTotalCostUsd(projectId, env, from, to);
        BigDecimal ledger = ledgerSpendRepository.sumConfirmedSpend(organizationId, projectId, env, from, to,
                AccountingAmount.Kind.ACTUAL, AccountingAmount.PricingStatus.PRICED);
        BigDecimal legacySpent = legacy == null ? BigDecimal.ZERO : legacy;
        BigDecimal ledgerSpent = ledger == null ? BigDecimal.ZERO : ledger;
        // 지출에 반영되지 않은 이벤트 수. 0원으로 숨기지 않고 따로 알려준다.
        long unreflected = ledgerSpendRepository.countUnreflected(organizationId, projectId, env, from, to);
        return new LedgerSpendSummaryResponse(projectId, env, yearMonth.toString(),
                legacySpent, ledgerSpent, legacySpent.add(ledgerSpent), unreflected, "USD");
    }

    /** 정산 확인 현황: 미정산 이벤트를 분류 규칙에 따라 UNPRICED / RECONCILIATION_REQUIRED로 나눠 센다. */
    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse getReconciliation(String organizationId, String projectId,
                                                           String environment, LedgerPeriod period) {
        requireProject(organizationId, projectId);
        String env = blankToNull(environment);
        long unpriced = 0L;
        long required = 0L;
        // 미정산 이벤트를 하나씩 분류기에 넣어 상태별로 센다.
        for (var row : queryRepository.findUnsettledRows(organizationId, projectId, env,
                period.from(), period.to(), AccountingAmount.Kind.ACTUAL)) {
            ReconciliationState state = ReconciliationClassifier.classifyByPricing(
                    AccountingStatus.valueOf(row.getAccountingStatus()),
                    CallStatus.valueOf(row.getCallStatus()),
                    SettlementState.valueOf(row.getSettlementState()),
                    row.getLatestActualPricingStatus());
            if (state == ReconciliationState.UNPRICED) {
                unpriced++;
            } else if (state == ReconciliationState.RECONCILIATION_REQUIRED) {
                required++;
            }
        }
        return new ReconciliationSummaryResponse(projectId, env, period.from(), period.to(), unpriced, required);
    }

    // 프로젝트가 이 조직 소유가 아니면 NOT_FOUND. (존재 여부도 알려주지 않는다)
    private void requireProject(String organizationId, String projectId) {
        projectRepository.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
    }

    // 빈 문자열 환경은 "전체 환경"으로 취급한다.
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
