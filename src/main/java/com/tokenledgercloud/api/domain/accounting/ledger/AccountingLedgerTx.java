package com.tokenledgercloud.api.domain.accounting.ledger;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.AccountingStatus;
import com.tokenledgercloud.api.domain.accounting.SettlementState;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.accounting.repository.UsageAccountingValueRepository;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 원장 기록의 트랜잭션 단위. 별도 빈으로 둬서 호출이 항상 트랜잭션 프록시를 거치게 한다.
 * (UsageLogWriter와 같은 구조)
 */
// 스프링 빈으로 등록한다.
@Component
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class AccountingLedgerTx {

    // 새 방식 이벤트의 레거시 금액 컬럼에 넣는 값. 권위 있는 금액은 원장에만 있다.
    private static final String LEDGER_PRICING_VERSION = "accounting-ledger";
    private static final String LEDGER_SOURCE_TYPE = "observe";

    // usage_events 저장소.
    private final UsageLogRepository usageLogRepository;
    // usage_accounting_values 저장소.
    private final UsageAccountingValueRepository valueRepository;

    /**
     * 이벤트와 첫 번째 원장 값을 한 트랜잭션에 저장한다. 하나라도 실패하면 둘 다 롤백된다.
     * 항상 새 트랜잭션에서 실행해서, 중복 키 오류가 호출자의 트랜잭션을 망가뜨리지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String insertEventWithValue(AccountingLedgerCommand command) {
        // usage_events 행을 만든다. 상태는 PENDING(신규 수집)으로 시작한다.
        UsageLog event = UsageLog.builder()
                .organizationId(command.organizationId())
                .projectId(command.projectId())
                .apiKeyId(command.apiKeyId())
                .environment(command.environment())
                .requestId(command.requestId())
                .eventId(command.eventId())
                .payloadFingerprint(command.payloadFingerprint())
                // 새 계약의 버전과 토큰 의미를 그대로 저장한다. (기존 값 legacy-v0, LEGACY_UNKNOWN으로 남지 않게 한다)
                .schemaVersion(command.schemaVersion())
                .tokenSemantics(command.tokenSemantics().name())
                .provider(command.provider())
                .model(command.model())
                .promptTokens(command.promptTokens())
                .completionTokens(command.completionTokens())
                .reasoningTokens(command.reasoningTokens())
                .cachedPromptTokens(command.cachedPromptTokens())
                .totalTokens(command.totalTokens())
                // 레거시 금액 컬럼은 NOT NULL이라 0을 넣는다. 이 행은 기존 조회에서 필터로 제외된다.
                .promptCostUsd(BigDecimal.ZERO)
                .completionCostUsd(BigDecimal.ZERO)
                .reasoningCostUsd(BigDecimal.ZERO)
                .cachedPromptCostUsd(BigDecimal.ZERO)
                .totalCostUsd(BigDecimal.ZERO)
                .pricingVersion(LEDGER_PRICING_VERSION)
                .sourceType(LEDGER_SOURCE_TYPE)
                .occurredAt(command.occurredAt())
                .accountingStatus(AccountingStatus.PENDING.name())
                .callStatus(command.callStatus().name())
                .settlementState(settlementOf(command).name())
                // 기록 주체도 UNKNOWN이 아니라 호출자가 넘긴 값으로 남긴다.
                .actorType(command.actorType())
                .actorId(command.actorId())
                .build();
        // 저장하고 바로 flush해서, 중복 키 오류가 이 트랜잭션 안에서 터지게 한다.
        UsageLog saved = usageLogRepository.saveAndFlush(event);

        // 사용량을 아는 호출만 원장 값(개정 1)을 쌓는다. 사용량을 모르면 값을 만들지 않는다.
        if (command.amount() != null) {
            valueRepository.saveAndFlush(UsageAccountingValue.of(
                    saved.getId(), 1, command.amount(), command.pricingSnapshotId(),
                    command.evidenceReference(), command.reasonCode(), command.actorType(), command.actorId()));
        }
        // 저장된 이벤트 id를 돌려준다.
        return saved.getId();
    }

    /**
     * 같은 (프로젝트, 환경, eventId)의 이벤트를 새 트랜잭션에서 읽는다.
     * 동시에 들어온 다른 요청이 방금 커밋한 행도 보려면 새 트랜잭션이어야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UsageLog> findEvent(String projectId, String environment, String eventId) {
        return usageLogRepository.findByProjectIdAndEnvironmentAndEventId(projectId, environment, eventId);
    }

    /**
     * 같은 (프로젝트, 환경, requestId)의 이벤트를 새 트랜잭션에서 읽는다.
     * 다른 eventId가 같은 requestId를 쓰는 충돌을 알아내려는 용도다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UsageLog> findEventByRequestId(String projectId, String environment, String requestId) {
        return usageLogRepository.findByProjectIdAndEnvironmentAndRequestId(projectId, environment, requestId);
    }

    // 정산 상태 규칙: 실제 사용량에 가격까지 확정되면 SETTLED, 그 외(추정, 가격 미상, 사용량 미상)는 UNSETTLED.
    static SettlementState settlementOf(AccountingLedgerCommand command) {
        AccountingAmount amount = command.amount();
        if (amount != null
                && amount.kind() == AccountingAmount.Kind.ACTUAL
                && amount.pricingStatus() == AccountingAmount.PricingStatus.PRICED) {
            return SettlementState.SETTLED;
        }
        return SettlementState.UNSETTLED;
    }
}
