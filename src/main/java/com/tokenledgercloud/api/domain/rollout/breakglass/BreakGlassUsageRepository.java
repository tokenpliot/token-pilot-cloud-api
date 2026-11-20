package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.tokenledgercloud.api.domain.accounting.AccountingAmount;
import com.tokenledgercloud.api.domain.accounting.entity.UsageAccountingValue;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;

/**
 * break-glass로 우회한 호출의 원장 조회. 읽기 전용이다. (이슈 #19)
 */
public interface BreakGlassUsageRepository extends Repository<UsageLog, String> {

	// metadata에 grant id 문자열이 들어 있는 이벤트 후보. H2와 MySQL의 JSON 함수가 달라서 문자열 LIKE로 먼저 거르고,
	// 키가 정확히 break_glass_grant_id인지는 서비스가 JSON을 파싱해 다시 확인한다.
	@Query("""
		select u
		from UsageLog u
		where u.organizationId = :organizationId
		  and u.projectId = :projectId
		  and u.environment = :environment
		  and cast(u.metadataJson as String) like :pattern
		""")
	List<UsageLog> findCandidates(@Param("organizationId") String organizationId,
			@Param("projectId") String projectId,
			@Param("environment") String environment,
			@Param("pattern") String pattern);

	// 이벤트별 최신 개정본. #11 원장 조회와 같은 규칙이다.
	@Query("""
		select v
		from UsageAccountingValue v
		where v.usageEventId in :usageEventIds
		  and v.valueKind = :kind
		  and v.revision = (select max(v2.revision) from UsageAccountingValue v2
				where v2.usageEventId = v.usageEventId and v2.valueKind = v.valueKind)
		""")
	List<UsageAccountingValue> findLatestValues(@Param("usageEventIds") Collection<String> usageEventIds,
			@Param("kind") AccountingAmount.Kind kind);
}
