package com.tokenledgercloud.api.domain.accounting.observe;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;

/**
 * 원장 조회 전용 저장소. 읽기만 하므로 Repository(표식 인터페이스)만 상속하고 저장 메서드는 열지 않는다.
 * 모든 쿼리는 조직과 프로젝트로 범위를 좁히고, 기존(LEGACY_UNVERIFIED) 행은 제외한다.
 */
public interface ObserveLedgerQueryRepository extends Repository<UsageLog, String> {

	/** 수집 현황 한 줄 요약. */
	interface IngestionRow {
		Long getEventCount();               // 기간 안의 원장 이벤트 수
		LocalDateTime getLastReceivedAt();  // 서버가 마지막으로 받은 시각
		LocalDateTime getLastOccurredAt();  // 가장 최근 호출 발생 시각
		Long getUnsettledCount();           // 아직 정산되지 않은 이벤트 수
	}

	/** 정산 확인 분류에 필요한 이벤트 한 줄. */
	interface ReconciliationRow {
		String getAccountingStatus();   // 회계 검증 상태
		String getCallStatus();         // 호출 결과 상태
		String getSettlementState();    // 정산 상태
		// 최신 실제(ACTUAL) 값의 가격 상태. 실제 값이 아직 없으면 null이다.
		AccountingAmount.PricingStatus getLatestActualPricingStatus();
	}

	/** 가격 누락 집계의 한 줄 (공급자/모델별). */
	interface UnpricedRow {
		String getProvider();   // 공급자
		String getModel();      // 모델
		Long getEventCount();   // 가격 누락 이벤트 수
		Long getTotalTokens();  // 그 이벤트들의 토큰 합계
	}

	// 기간 안의 원장 이벤트 수, 마지막 수신/발생 시각, 미정산 수를 한 번에 센다.
	@Query("""
		select count(u) as eventCount,
			   max(u.createdAt) as lastReceivedAt,
			   max(u.occurredAt) as lastOccurredAt,
			   sum(case when u.settlementState = 'UNSETTLED' then 1 else 0 end) as unsettledCount
		from UsageLog u
		where u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus <> 'LEGACY_UNVERIFIED'
		""")
	IngestionRow findIngestionRow(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	// 지정한 회계 상태(예: QUARANTINED)의 이벤트 수를 센다.
	@Query("""
		select count(u)
		from UsageLog u
		where u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus = :accountingStatus
		""")
	long countByAccountingStatus(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("accountingStatus") String accountingStatus);

	// 격리된 이벤트의 "최신 개정본" 중 실제(ACTUAL)이고 가격이 확정된(PRICED) 금액을 더한다.
	// 서브쿼리가 이벤트별 가장 큰 revision만 고르므로, 정정 전의 옛 값이 이중으로 더해지지 않는다.
	@Query("""
		select sum(v.amount)
		from UsageAccountingValue v, UsageLog u
		where v.usageEventId = u.id
		  and u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus = 'QUARANTINED'
		  and v.valueKind = :kind
		  and v.pricingStatus = :pricingStatus
		  and v.revision = (select max(v2.revision) from UsageAccountingValue v2
				where v2.usageEventId = v.usageEventId and v2.valueKind = v.valueKind)
		""")
	BigDecimal sumQuarantinedAmount(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("kind") AccountingAmount.Kind kind,
			@Param("pricingStatus") AccountingAmount.PricingStatus pricingStatus);

	// 최신 실제(ACTUAL) 개정본이 가격 미상(UNPRICED)인 이벤트를 공급자/모델별로 센다.
	// /reconciliation의 UNPRICED 건수와 같은 기준이 되도록 분류기와 같은 조건(기존 행, 격리, 정산 완료, 사용량 미상 제외)을 건다.
	@Query("""
		select u.provider as provider,
			   u.model as model,
			   count(u) as eventCount,
			   sum(u.totalTokens) as totalTokens
		from UsageAccountingValue v, UsageLog u
		where v.usageEventId = u.id
		  and u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and v.valueKind = :kind
		  and v.pricingStatus = :pricingStatus
		  and v.revision = (select max(v2.revision) from UsageAccountingValue v2
				where v2.usageEventId = v.usageEventId and v2.valueKind = v.valueKind)
		  and u.accountingStatus <> 'LEGACY_UNVERIFIED'
		  and u.accountingStatus <> 'QUARANTINED'
		  and u.settlementState = 'UNSETTLED'
		  and u.callStatus <> 'USAGE_UNKNOWN'
		group by u.provider, u.model
		order by count(u) desc
		""")
	List<UnpricedRow> findUnpriced(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("kind") AccountingAmount.Kind kind,
			@Param("pricingStatus") AccountingAmount.PricingStatus pricingStatus);

	// 아직 정산되지 않았거나 격리된 원장 이벤트를 상태 컬럼과 "최신 실제 값의 가격 상태"와 함께 읽는다.
	// 스칼라 서브쿼리가 이벤트별로 가장 큰 revision의 가격 상태 하나만 골라준다.
	// 분류 규칙은 ReconciliationClassifier에만 두기 위해, 여기서는 규칙을 판단하지 않고 값만 읽는다.
	@Query("""
		select u.accountingStatus as accountingStatus,
			   u.callStatus as callStatus,
			   u.settlementState as settlementState,
			   (select v.pricingStatus
				from UsageAccountingValue v
				where v.usageEventId = u.id
				  and v.valueKind = :kind
				  and v.revision = (select max(v2.revision) from UsageAccountingValue v2
						where v2.usageEventId = v.usageEventId and v2.valueKind = v.valueKind)
			   ) as latestActualPricingStatus
		from UsageLog u
		where u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and (:environment is null or u.environment = :environment)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		  and u.accountingStatus <> 'LEGACY_UNVERIFIED'
		  and (u.settlementState = 'UNSETTLED' or u.accountingStatus = 'QUARANTINED')
		""")
	List<ReconciliationRow> findUnsettledRows(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("kind") AccountingAmount.Kind kind);
}
