package com.tokenledgercloud.api.domain.ingestion.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.AccountingStatus;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * Observe 이벤트를 예산 범위에 귀속할 수 있는지 판정한다(이슈 #41).
 *
 * <p>정책(#14)과 예약(#17)은 프로젝트에 등록된 환경을 기준으로 예산을 잡는다. 등록되지 않은 환경으로 들어온 비용은
 * 어느 환경 예산에도 잡히지 않으므로 미귀속이다. 이런 이벤트는 거절하지 않고 QUARANTINED로 기록해서,
 * 비용 기록은 남기되 지출 합계에서는 빼고 미귀속 비용 API에 따로 보이게 한다.
 */
// 스프링 빈으로 등록한다.
@Component
// final 필드를 받는 생성자를 롬복이 만들어 준다.
@RequiredArgsConstructor
public class ObserveEventAttribution {

	// 프로젝트에 등록된 환경 목록.
	private final ProjectEnvironmentRepository environments;

	/**
	 * 새 이벤트의 처음 회계 상태를 정한다.
	 *
	 * @return 등록된 환경이면 PENDING, 등록되지 않은 환경이면 QUARANTINED.
	 *         프로젝트에 등록된 환경이 하나도 없으면 판단 근거가 없으므로 PENDING이다.
	 */
	@Transactional(readOnly = true)
	public AccountingStatus initialStatus(String organizationId, String projectId, String environment) {
		List<ProjectEnvironment> registered = environments.findByProjectId(projectId).stream()
				.filter(registeredEnvironment -> organizationId.equals(registeredEnvironment.getOrganizationId()))
				.toList();
		if (registered.isEmpty()) {
			return AccountingStatus.PENDING;
		}
		// PolicyService와 같이 대소문자까지 정확히 같아야 한다. (Prod와 prod는 다른 범위)
		boolean known = registered.stream()
				.anyMatch(registeredEnvironment -> registeredEnvironment.getEnvironment().equals(environment));
		return known ? AccountingStatus.PENDING : AccountingStatus.QUARANTINED;
	}
}
