package com.tokenledgercloud.api.domain.project.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;

import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.dto.ProjectCreateRequest;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.usage.repository.UsageLogRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

	@InjectMocks
	private ProjectService projectService;

	@Mock
	private ProjectRepository projectRepository;

	@Mock
	private ProjectEnvironmentRepository projectEnvironmentRepository;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private UsageLogRepository usageLogRepository;

	@Mock
	private MonthlyBudgetSettingRepository budgetRepository;

	private TestingAuthenticationToken authentication;
	private Member member;

	@BeforeEach
	void setUp() {
		authentication = new TestingAuthenticationToken("user@test.com", null);
		member = Member.builder()
			.id("member-1")
			.email("user@test.com")
			.name("tester")
			.role(Role.USER)
			.provider("local")
			.build();

		given(memberRepository.findByEmail("user@test.com")).willReturn(Optional.of(member));
	}

	@Test
	void createProjectRejectsDuplicateProjectKeyAsConflict() {
		ProjectCreateRequest request = new ProjectCreateRequest(
			"API Gateway",
			"api-gateway",
			List.of("prod"),
			"gpt-4o"
		);
		given(projectRepository.existsByOrganizationIdAndProjectKey("default-org", "api-gateway"))
			.willReturn(true);

		assertThatThrownBy(() -> projectService.createProject(authentication, request))
			.isInstanceOfSatisfying(ApiException.class, exception ->
				org.assertj.core.api.Assertions.assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)
			);
	}

	@Test
	void getProjectsParsesStatusFilterToEnum() {
		given(projectRepository.findProjects("default-org", null, ProjectStatus.ACTIVE)).willReturn(List.of());

		projectService.getProjects(authentication, null, "active");

		verify(projectRepository).findProjects("default-org", null, ProjectStatus.ACTIVE);
	}

	@Test
	void getProjectsRejectsUnsupportedStatus() {
		assertThatThrownBy(() -> projectService.getProjects(authentication, null, "paused"))
			.isInstanceOfSatisfying(ApiException.class, exception ->
				org.assertj.core.api.Assertions.assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT)
			);
	}

	@Test
	void getProjectRankingQueriesActiveProjectsWithEnumStatus() {
		Project project = Project.builder()
			.id("project-1")
			.organizationId("default-org")
			.projectKey("api-gateway")
			.name("API Gateway")
			.status(ProjectStatus.ACTIVE)
			.build();
		given(projectRepository.findProjects("default-org", null, ProjectStatus.ACTIVE)).willReturn(List.of(project));
		given(usageLogRepository.findTopModelsByProject(eq(null), any(), any())).willReturn(List.of());
		given(usageLogRepository.findProjectUsageRanking(any(), eq(null), any(), any(), any(Pageable.class)))
			.willReturn(List.of());

		projectService.getProjectRanking(authentication, null, "month", 10);

		verify(projectRepository).findProjects("default-org", null, ProjectStatus.ACTIVE);
	}
}
