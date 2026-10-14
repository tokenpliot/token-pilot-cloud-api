package com.tokenledgercloud.api.domain.accounting.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;

/**
 * 회계 원장 리포지토리. 원장은 추가만 하므로 삭제/수정용 메서드는 만들지 않는다.
 */
public interface UsageAccountingValueRepository extends JpaRepository<UsageAccountingValue, String> {

    /**
     * 이벤트의 특정 종류(ESTIMATED/ACTUAL) 값 중 개정 번호가 가장 큰 최신 값을 조회한다.
     * 집계는 이 최신 개정본만 사용해야 이중 계산이 없다.
     */
    Optional<UsageAccountingValue> findFirstByUsageEventIdAndValueKindOrderByRevisionDesc(
            String usageEventId, AccountingAmount.Kind valueKind);
}
