package com.tokenledgercloud.api.domain.accounting.observe;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;

/**
 * 예산 계산용 원장 조회 저장소. 예산은 프로젝트나 환경이 비어 있는(조직 전체) 경우가 있어서,
 * 프로젝트와 환경은 null을 허용한다. (조회 API용 ObserveLedgerQueryRepository와 달리)
 */
public interface LedgerSpendRepository extends Repository<UsageLog, String> {

	// 예산에 반영할 "확정 지출": 최신 개정본이 실제(ACTUAL)이고 가격이 확정된(PRICED) 금액의 합.
	// 기존(LEGACY_UNVERIFIED) 행은 기존 합계 쿼리가 따로 더하므로 여기서 빼고, 격리(QUARANTINED) 행은
	// 어느 프로젝트의 지출인지 불확실하므로 뺀다. 대상이 없으면 null이다.
	@Query("""
		select sum(v.amount)
		from UsageAccountingValue v, UsageLog u
		where v.usageEventId = u.id
		  and u.organizationId = :organizationId
		  and (:projectId is null or u.projectId = :projectId)
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus <> 'LEGACY_UNVERIFIED'
		  and u.accountingStatus <> 'QUARANTINED'
		  and v.valueKind = :kind
		  and v.pricingStatus = :pricingStatus
		  and v.revision = (select max(v2.revision) from UsageAccountingValue v2
				where v2.usageEventId = v.usageEventId and v2.valueKind = v.valueKind)
		""")
	BigDecimal sumConfirmedSpend(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("kind") AccountingAmount.Kind kind,
			@Param("pricingStatus") AccountingAmount.PricingStatus pricingStatus);

	// 지출에 반영되지 않은 이벤트 수: 원장 이벤트 중 정산이 끝나지 않았거나(UNSETTLED) 격리된(QUARANTINED) 것.
	// 가격 미상, 사용량 미상, 추정만 있는 이벤트와, 정산 완료로 기록되었어도 지출에서 빠지는 격리 이벤트가 들어간다.
	// 위의 sumConfirmedSpend가 빼는 이벤트가 전부 여기에 세어져야 어디에도 안 보이는 이벤트가 없다.
	@Query("""
		select count(u)
		from UsageLog u
		where u.organizationId = :organizationId
		  and (:projectId is null or u.projectId = :projectId)
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus <> 'LEGACY_UNVERIFIED'
		  and (u.settlementState = 'UNSETTLED' or u.accountingStatus = 'QUARANTINED')
		""")
	long countUnreflected(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
