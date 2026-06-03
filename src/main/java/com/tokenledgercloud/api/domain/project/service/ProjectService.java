package com.tokenledgercloud.api.domain.project.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.budget.entity.MonthlyBudgetSetting;
import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.dto.ProjectCreateRequest;
import com.tokenledgercloud.api.domain.project.dto.ProjectCreateResponse;
import com.tokenledgercloud.api.domain.project.dto.ProjectDetailResponse;
import com.tokenledgercloud.api.domain.project.dto.ProjectListItemResponse;
import com.tokenledgercloud.api.domain.project.dto.ProjectListResponse;
import com.tokenledgercloud.api.domain.project.dto.ProjectRankingItemResponse;
import com.tokenledgercloud.api.domain.project.dto.ProjectRankingResponse;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.domain.usage.repository.projection.KpiProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ProjectTopModelProjection;
import com.tokenledgercloud.api.domain.usage.repository.projection.ProjectUsageRankingProjection;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectService {

        private static final String DEFAULT_ORGANIZATION_ID = "default-org";
        private static final int MAX_RANKING_LIMIT = 100;

        private final ProjectRepository projectRepository;
        private final ProjectEnvironmentRepository projectEnvironmentRepository;
        private final MemberRepository memberRepository;
        private final UsageLogRepository usageLogRepository;
        private final MonthlyBudgetSettingRepository budgetRepository;

	@Transactional
	public ProjectCreateResponse createProject(Authentication authentication, ProjectCreateRequest request) {
		getMember(authentication);
		if (projectRepository.existsByOrganizationIdAndProjectKey(DEFAULT_ORGANIZATION_ID, request.projectKey())) {
			throw new ApiException(ErrorCode.CONFLICT, "이미 사용 중인 projectKey입니다.");
		}

                Project project = projectRepository.save(Project.builder()
                        .organizationId(DEFAULT_ORGANIZATION_ID)
                        .name(request.name())
                        .projectKey(request.projectKey())
                        .status(ProjectStatus.ACTIVE)
                        .defaultModel(request.defaultModel())
                        .build());
		List<ProjectEnvironment> environments = request.environments().stream()
			.distinct()
			.map(environment -> ProjectEnvironment.builder()
				.organizationId(DEFAULT_ORGANIZATION_ID)
				.projectId(project.getId())
				.environment(environment)
				.build())
			.toList();
		projectEnvironmentRepository.saveAll(environments);

		return toCreateResponse(project);
	}

	@Transactional(readOnly = true)
	public ProjectListResponse getProjects(Authentication authentication, String environment, String status) {
		getMember(authentication);
		List<Project> projects = projectRepository.findProjects(
				DEFAULT_ORGANIZATION_ID,
				blankToNull(environment),
				parseStatus(status)
			);
		Map<String, List<String>> environmentsByProjectId = environmentsByProjectId(projects);

		List<ProjectListItemResponse> items = projects.stream()
			.map(project -> toListItemResponse(project, environmentsByProjectId.getOrDefault(project.getId(), List.of())))
			.toList();

		return new ProjectListResponse(items);
	}

	@Transactional(readOnly = true)
	public ProjectDetailResponse getProject(Authentication authentication, String projectId) {
		getMember(authentication);
		Project project = getProject(projectId);
		TimeRange month = currentMonthRange();
		KpiProjection kpi = usageLogRepository.getKpi(project.getId(), month.from(), month.to());
		List<String> environments = projectEnvironmentRepository.findByProjectId(project.getId())
			.stream()
			.map(ProjectEnvironment::getEnvironment)
			.toList();

		return new ProjectDetailResponse(
		        project.getId(),
		        project.getName(),
		        project.getProjectKey(),
		        project.getStatus().name(),
		        environments,
		        project.getDefaultModel(),
		        kpi.getTotalCost(),
		        kpi.getTotalTokens()
		);

	}

	@Transactional(readOnly = true)
	public ProjectRankingResponse getProjectRanking(
	        Authentication authentication,
	        String environment,
	        String period,
	        Integer limit
	) {
	        getMember(authentication);
	        validateLimit(limit);

	        TimeRange range = resolvePeriod(period);
	        String environmentFilter = blankToNull(environment);
		List<Project> projects = projectRepository.findProjects(DEFAULT_ORGANIZATION_ID, environmentFilter, ProjectStatus.ACTIVE);
	        if (projects.isEmpty()) {
	                return new ProjectRankingResponse(List.of());
	        }
		Map<String, Project> projectById = projects.stream()
			.collect(Collectors.toMap(Project::getId, project -> project));
		Map<String, String> topModelByProjectId = usageLogRepository
			.findTopModelsByProject(environmentFilter, range.from(), range.to())
			.stream()
			.collect(Collectors.toMap(ProjectTopModelProjection::getProjectId, ProjectTopModelProjection::getTopModel, (first, second) -> first));

		List<ProjectUsageRankingProjection> rankings = usageLogRepository.findProjectUsageRanking(
			projectById.keySet(),
			environmentFilter,
			range.from(),
			range.to(),
			Pageable.ofSize(limit)
		);

		List<ProjectRankingItemResponse> items = new ArrayList<>();
		for (int i = 0; i < rankings.size(); i++) {
			ProjectUsageRankingProjection row = rankings.get(i);
			Project project = projectById.get(row.getProjectId());
			if (project == null) {
				continue;
			}
			items.add(toRankingItem(
				row,
				project,
				environmentFilter,
				topModelByProjectId.getOrDefault(project.getId(), project.getDefaultModel()),
				i + 1
			));
		}

		return new ProjectRankingResponse(items);
	}

	private ProjectRankingItemResponse toRankingItem(
		ProjectUsageRankingProjection row,
		Project project,
		String environment,
		String topModel,
		int rank
	) {
		return new ProjectRankingItemResponse(
			project.getId(),
			project.getName(),
			environment,
			topModel,
			row.getTotalCost(),
			calculateBudgetUsagePercent(row.getTotalCost(), findBudgetLimit(project.getId(), environment)),
			rank
		);
	}

	private BigDecimal findBudgetLimit(String projectId, String environment) {
		return budgetRepository.findFirstByOrganizationIdAndProjectIdAndEnvironment(
				DEFAULT_ORGANIZATION_ID,
				projectId,
				environment
			)
			.map(MonthlyBudgetSetting::getLimitUsd)
			.orElse(null);
	}

	private int calculateBudgetUsagePercent(BigDecimal costUsd, BigDecimal budgetLimitUsd) {
		if (budgetLimitUsd == null || budgetLimitUsd.compareTo(BigDecimal.ZERO) <= 0) {
			return 0;
		}
		return costUsd
			.multiply(BigDecimal.valueOf(100))
			.divide(budgetLimitUsd, 0, RoundingMode.HALF_UP)
			.intValue();
	}

	private Project getProject(String projectId) {
		return projectRepository.findByIdAndOrganizationId(projectId, DEFAULT_ORGANIZATION_ID)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
	}

	private ProjectCreateResponse toCreateResponse(Project project) {
	        return new ProjectCreateResponse(project.getId(), project.getName(), project.getProjectKey(), project.getStatus().name());
	}

	private ProjectListItemResponse toListItemResponse(Project project, List<String> environments) {
	        return new ProjectListItemResponse(
	                project.getId(),
	                project.getName(),
	                environments,
	                project.getDefaultModel(),
	                project.getStatus().name()
	        );
	}
	private Map<String, List<String>> environmentsByProjectId(List<Project> projects) {
		if (projects.isEmpty()) {
			return Map.of();
		}
		return projectEnvironmentRepository.findByProjectIdIn(projects.stream().map(Project::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(
				ProjectEnvironment::getProjectId,
				LinkedHashMap::new,
				Collectors.mapping(ProjectEnvironment::getEnvironment, Collectors.toList())
			));
	}

	private void validateLimit(Integer limit) {
		if (limit == null || limit < 1 || limit > MAX_RANKING_LIMIT) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "limit 값이 허용 범위를 벗어났습니다.");
		}
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private ProjectStatus parseStatus(String status) {
		String normalized = blankToNull(status);
		if (normalized == null) {
			return null;
		}
		try {
			return ProjectStatus.valueOf(normalized.toUpperCase());
		} catch (IllegalArgumentException exception) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "지원하지 않는 프로젝트 상태입니다.");
		}
	}

	private Member getMember(Authentication authentication) {
		String identifier = authentication.getName();
		return memberRepository.findByEmail(identifier)
			.or(() -> memberRepository.findByProviderId(identifier))
			.orElseThrow(() -> new IllegalStateException("Member not found"));
	}

	private TimeRange currentMonthRange() {
		LocalDate today = LocalDate.now();
		return new TimeRange(today.withDayOfMonth(1).atStartOfDay(), today.plusDays(1).atStartOfDay());
	}

	private TimeRange resolvePeriod(String period) {
		LocalDate today = LocalDate.now();

		return switch (period == null ? "month" : period) {
			case "today" -> new TimeRange(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
			case "week" -> new TimeRange(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
			case "month" -> currentMonthRange();
			default -> new TimeRange(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
		};
	}

	private record TimeRange(LocalDateTime from, LocalDateTime to) {
	}
}
