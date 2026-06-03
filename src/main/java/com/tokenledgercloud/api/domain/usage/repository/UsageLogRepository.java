package com.tokenledgercloud.api.domain.usage.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import com.tokenledgercloud.api.domain.usage.entity.UsageLog;
import com.tokenledgercloud.api.domain.usage.repository.projection.KpiProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ModelCostSummaryProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ProjectCostRankingProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.CostTrendProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ProjectTopModelProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ProjectUsageRankingProjection;
public interface UsageLogRepository extends JpaRepository<UsageLog, String> {

	Optional<UsageLog> findByProjectIdAndEnvironmentAndRequestId(String projectId, String environment, String requestId);

	@Query("""
	select coalesce(sum(u.totalCostUsd), 0) as totalCost,
	       coalesce(sum(u.totalTokens), 0) as totalTokens,
	       0 as blockedRequests
	from UsageLog u
	where (:projectId is null or u.projectId = :projectId)
	  and u.occurredAt >= :from
	  and u.occurredAt < :to
    """)
	KpiProjection getKpi(String projectId, LocalDateTime from, LocalDateTime to);

	@Query("""
		select u.model as modelId,
		       coalesce(sum(u.totalCostUsd), 0) as totalCost,
		       coalesce(sum(u.totalTokens), 0) as totalTokens
		from UsageLog u
		where (:projectId is null or u.projectId = :projectId)
		  and u.occurredAt >= :from
		  and u.occurredAt < :to
		group by u.model
		order by sum(u.totalCostUsd) desc
	""")
	List<ModelCostSummaryProjection> findModelCostSummary(
		String projectId,
		LocalDateTime from,
		LocalDateTime to
	);

	@Query("""
		select u.projectId as projectId,
		       coalesce(sum(u.totalCostUsd), 0) as totalCost,
		       coalesce(sum(u.totalTokens), 0) as totalTokens
		from UsageLog u
		where u.occurredAt >= :from
		  and u.occurredAt < :to
		group by u.projectId
		order by sum(u.totalCostUsd) desc
	""")
	List<ProjectCostRankingProjection> findProjectCostRanking(
		LocalDateTime from,
		LocalDateTime to
	);

	List<UsageLog> findTop20ByOrderByOccurredAtDesc();
	List<UsageLog> findByProjectIdOrderByOccurredAtDesc(String projectId);

	@Query(value = """
		select date_format(u.occurred_at, '%Y-%m-%d') as bucket,
			coalesce(sum(u.prompt_cost_usd), 0) as promptCostUsd,
			coalesce(sum(u.completion_cost_usd), 0) as completionCostUsd,
			coalesce(sum(u.reasoning_cost_usd), 0) as reasoningCostUsd
		from usage_events u
		where (:projectId is null or u.project_id = :projectId)
		and (:environment is null or u.environment = :environment)
		and u.occurred_at >= :from
		and u.occurred_at < :to
		group by date_format(u.occurred_at, '%Y-%m-%d')
		order by bucket asc
	""", nativeQuery = true)
	List<CostTrendProjection> findCostTrend(
		String projectId,
		String environment,
		LocalDateTime from,
		LocalDateTime to
	);
	@Query("""
		select coalesce(sum(u.totalCostUsd), 0)
		from UsageLog u
		where (:projectId is null or u.projectId = :projectId)
		and (:environment is null or u.environment = :environment)
		and u.occurredAt >= :from
		and u.occurredAt < :to

	""")

	java.math.BigDecimal sumTotalCostUsd(

		String projectId,
		String environment,
		LocalDateTime from,
		LocalDateTime to

	);

	@Query("""
		select u
		from UsageLog u
		where (:projectId is null or u.projectId = :projectId)
		and (:environment is null or u.environment = :environment)
		and (:provider is null or u.provider = :provider)
		and (:model is null or u.model = :model)
		and (:cursorOccurredAt is null or u.occurredAt < :cursorOccurredAt)
		order by u.occurredAt desc
	""")
	List<UsageLog> findRecentUsageEvents(
		String projectId,
		String environment,
		String provider,
		String model,
		LocalDateTime cursorOccurredAt,
		Pageable pageable
	);

	@Query(value = """
		select ranked.project_id as projectId,
			ranked.model as topModel
		from (
			select u.project_id,
				u.model,
				sum(u.total_cost_usd) as total_cost,
				row_number() over (
					partition by u.project_id
					order by sum(u.total_cost_usd) desc
				) as rn
			from usage_events u
			where u.occurred_at >= :from
			and u.occurred_at < :to
			and (:environment is null or u.environment = :environment)
			group by u.project_id, u.model
		) ranked
		where ranked.rn = 1
	""", nativeQuery = true)
	List<ProjectTopModelProjection> findTopModelsByProject(
		String environment,
		LocalDateTime from,
		LocalDateTime to
	);

	@Query("""
		select u.projectId as projectId,
			coalesce(sum(u.totalCostUsd), 0) as totalCost,
			coalesce(sum(u.totalTokens), 0) as totalTokens,
			max(u.occurredAt) as latestUsedAt
		from UsageLog u
		where u.projectId in :projectIds
		and (:environment is null or u.environment = :environment)
		and u.occurredAt >= :from
		and u.occurredAt < :to
		group by u.projectId
		order by sum(u.totalCostUsd) desc, max(u.occurredAt) desc
	""")
	List<ProjectUsageRankingProjection> findProjectUsageRanking(
		Collection<String> projectIds,
		String environment,
		LocalDateTime from,
		LocalDateTime to,
		Pageable pageable
	);
}
