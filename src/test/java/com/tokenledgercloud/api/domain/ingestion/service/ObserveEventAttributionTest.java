package com.tokenledgercloud.api.domain.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.tokenledgercloud.api.domain.accounting.AccountingStatus;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;

/** 미귀속 판정 규칙(#41)을 DB 없이 확인한다. */
@ExtendWith(MockitoExtension.class)
class ObserveEventAttributionTest {

	@Mock ProjectEnvironmentRepository environments;
	@InjectMocks ObserveEventAttribution attribution;

	private static ProjectEnvironment registered(String organizationId, String environment) {
		return ProjectEnvironment.builder().id("env-" + environment).organizationId(organizationId)
				.projectId("project-1").environment(environment).build();
	}

	@Test
	void registeredEnvironmentIsAttributed() {
		when(environments.findByProjectId("project-1"))
				.thenReturn(List.of(registered("org-1", "prod"), registered("org-1", "staging")));

		assertThat(attribution.initialStatus("org-1", "project-1", "prod")).isEqualTo(AccountingStatus.PENDING);
		assertThat(attribution.initialStatus("org-1", "project-1", "staging")).isEqualTo(AccountingStatus.PENDING);
	}

	@Test
	void unregisteredEnvironmentIsQuarantined() {
		when(environments.findByProjectId("project-1")).thenReturn(List.of(registered("org-1", "prod")));

		// 오타로 생긴 환경은 어느 환경 예산에도 잡히지 않는다.
		assertThat(attribution.initialStatus("org-1", "project-1", "prd")).isEqualTo(AccountingStatus.QUARANTINED);
	}

	@Test
	void environmentComparisonIsCaseSensitive() {
		when(environments.findByProjectId("project-1")).thenReturn(List.of(registered("org-1", "prod")));

		// PolicyService와 같은 규칙: Prod와 prod는 다른 범위다.
		assertThat(attribution.initialStatus("org-1", "project-1", "Prod")).isEqualTo(AccountingStatus.QUARANTINED);
	}

	@Test
	void environmentRegisteredUnderAnotherOrganizationDoesNotCount() {
		when(environments.findByProjectId("project-1"))
				.thenReturn(List.of(registered("org-1", "prod"), registered("org-other", "dev")));

		assertThat(attribution.initialStatus("org-1", "project-1", "dev")).isEqualTo(AccountingStatus.QUARANTINED);
	}

	@Test
	void projectWithoutRegisteredEnvironmentsIsNotQuarantined() {
		when(environments.findByProjectId("project-1")).thenReturn(List.of());

		// 판단 근거가 없으므로 기존처럼 PENDING이다.
		assertThat(attribution.initialStatus("org-1", "project-1", "anything")).isEqualTo(AccountingStatus.PENDING);
	}
}
