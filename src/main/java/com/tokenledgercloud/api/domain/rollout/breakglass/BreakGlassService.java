package com.tokenledgercloud.api.domain.rollout.breakglass;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenledgercloud.api.domain.event.entity.OperationalEvent;
import com.tokenledgercloud.api.domain.event.repository.OperationalEventRepository;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.ingestion.service.ProjectApiKeyAuthenticator;
import com.tokenledgercloud.api.domain.policy.OrganizationMembershipRepository;
import com.tokenledgercloud.api.domain.policy.PolicyAccess;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassActiveResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassGrantResponse;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassIssueRequest;
import com.tokenledgercloud.api.domain.rollout.breakglass.dto.BreakGlassRevokeRequest;
import com.tokenledgercloud.api.domain.rollout.metrics.RolloutMetrics;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * break-glass 발급·취소·조회. (이슈 #19)
 * - 한 프로젝트·환경에 유효한 grant는 하나뿐이다. 연장하려면 취소 후 다시 발급한다.
 * - 만료 시간은 필수이고 지나면 자동으로 효력이 없어진다. 별도 배치 없이 조회 시각으로 판정한다.
 * - 발급·취소는 grant 행과 운영 이벤트(operational_events)에 모두 남긴다.
 * - 권한은 #14의 PolicyAccess로 조직 멤버인지 확인한 뒤, 발급·취소는 OWNER/ADMIN/OPERATOR만 허용한다.
 *   장애 대응 중인 고객 운영자가 관리자를 기다리지 않고 실행할 수 있게 하기 위해서다.
 * 시각 인자 now는 UTC이며, 테스트에서 시각을 고정할 수 있도록 호출하는 쪽이 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class BreakGlassService {

	static final int DEFAULT_HISTORY_LIMIT = 50;
	static final int MAX_HISTORY_LIMIT = 200;
	private static final Set<String> OPERATE_ROLES = Set.of("OWNER", "ADMIN", "OPERATOR");

	private final BreakGlassGrantRepository grantRepository;
	private final ProjectRepository projectRepository;
	private final ProjectEnvironmentRepository environmentRepository;
	private final OperationalEventRepository eventRepository;
	private final PolicyAccess policyAccess;
	private final OrganizationMembershipRepository membershipRepository;
	private final ProjectApiKeyAuthenticator apiKeyAuthenticator;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Transactional
	public BreakGlassGrantResponse issue(Authentication authentication, BreakGlassIssueRequest request,
			LocalDateTime now) {
		PolicyAccess.Grant access = requireOperator(authentication, request.projectId());
		String organizationId = access.project().getOrganizationId();
		requireEnvironment(request.projectId(), request.environment());
		String scopeKey = BreakGlassGrant.scopeKey(organizationId, request.projectId(), request.environment());

		try {
			// 같은 범위를 차지한 grant가 있으면: 아직 유효하면 거절하고, 만료됐으면 범위만 비운다.
			grantRepository.findByActiveScopeKey(scopeKey).ifPresent(existing -> {
				if (existing.statusAt(now) == BreakGlassStatus.ACTIVE) {
					throw new ApiException(ErrorCode.CONFLICT,
							"이미 유효한 break-glass가 있습니다. 연장하려면 취소 후 다시 발급하세요. grantId=" + existing.getId());
				}
				existing.releaseScope();
				grantRepository.saveAndFlush(existing);
			});

			BreakGlassGrant grant = grantRepository.saveAndFlush(BreakGlassGrant.builder()
					.organizationId(organizationId)
					.projectId(request.projectId())
					.environment(request.environment())
					.reason(request.reason())
					.issuedBy(access.memberId())
					.issuedAt(now)
					.expiresAt(now.plusMinutes(request.durationMinutes()))
					.activeScopeKey(scopeKey)
					.build());

			recordEvent(grant, "WARNING", "BREAK_GLASS_ISSUED",
					"break-glass 발급: " + grant.getEnvironment() + " 범위의 Enforce를 " + request.durationMinutes()
							+ "분 동안 우회합니다.",
					access.memberId(), grant.getReason(), now);
			RolloutMetrics.breakGlass("ISSUED");
			return BreakGlassGrantResponse.of(grant, now);
		} catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
			// 다른 요청이 같은 범위에 먼저 발급했다. 유니크 제약 또는 버전 충돌로 이쪽만 실패한다.
			throw new ApiException(ErrorCode.CONFLICT, "같은 범위에 break-glass가 동시에 발급되었습니다. 현재 상태를 조회한 뒤 다시 시도하세요.");
		}
	}

	@Transactional
	public BreakGlassGrantResponse revoke(Authentication authentication, String grantId,
			BreakGlassRevokeRequest request, LocalDateTime now) {
		BreakGlassGrant grant = grantRepository.findById(grantId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "break-glass grant를 찾을 수 없습니다."));
		// 다른 조직의 grant는 존재 여부도 드러내지 않도록 PolicyAccess가 NOT_FOUND로 응답한다.
		PolicyAccess.Grant access = requireOperator(authentication, grant.getProjectId());
		if (grant.statusAt(now) != BreakGlassStatus.ACTIVE) {
			throw new ApiException(ErrorCode.CONFLICT, "이미 만료되었거나 취소된 break-glass입니다. status=" + grant.statusAt(now));
		}

		try {
			grant.revoke(access.memberId(), request.reason(), now);
			grantRepository.saveAndFlush(grant);
		} catch (OptimisticLockingFailureException exception) {
			throw new ApiException(ErrorCode.CONFLICT, "다른 요청이 이 break-glass를 먼저 변경했습니다. 현재 상태를 조회하세요.");
		}

		recordEvent(grant, "INFO", "BREAK_GLASS_REVOKED",
				"break-glass 취소: " + grant.getEnvironment() + " 범위의 Enforce 우회를 종료합니다.",
				access.memberId(), request.reason(), now);
		RolloutMetrics.breakGlass("REVOKED");
		return BreakGlassGrantResponse.of(grant, now);
	}

	/**
	 * Client SDK용 유효 조회. 회원 로그인 대신 판정 API와 같은 프로젝트 API 키로 인증한다(401·403은 인증기 규칙 그대로).
	 * SDK는 자기 프로젝트 id를 모를 수 있으므로 projectId를 생략하면 키의 프로젝트를 쓴다.
	 * projectId를 주면 키의 프로젝트와 같아야 하며, 다르면 다른 프로젝트의 존재 여부를 드러내지 않도록 404다.
	 * 인증기가 키의 마지막 사용 시각을 기록하므로 읽기 전용 트랜잭션이 아니다.
	 */
	@Transactional
	public BreakGlassActiveResponse getActiveForApiKey(String rawApiKey, String projectId, String environment,
			LocalDateTime now) {
		AuthenticatedProjectApiKey key = apiKeyAuthenticator.authenticate(rawApiKey, environment);
		String targetProject = projectId == null || projectId.isBlank() ? key.projectId() : projectId;
		if (!targetProject.equals(key.projectId())) {
			throw new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
		}
		return getActive(targetProject, environment, now);
	}

	/** 지금 유효한 grant가 있는지. 인증은 호출하는 쪽(getActiveForApiKey)이 끝낸 뒤에 부른다. */
	@Transactional(readOnly = true)
	BreakGlassActiveResponse getActive(String projectId, String environment, LocalDateTime now) {
		Project project = projectRepository.findById(projectId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
		requireEnvironment(projectId, environment);
		// 만료됐지만 아직 범위를 비우지 않은 grant도 있을 수 있으므로 상태를 다시 확인한다.
		BreakGlassGrantResponse grant = grantRepository
				.findByActiveScopeKey(BreakGlassGrant.scopeKey(project.getOrganizationId(), projectId, environment))
				.filter(found -> found.statusAt(now) == BreakGlassStatus.ACTIVE)
				.map(found -> BreakGlassGrantResponse.of(found, now))
				.orElse(null);
		return new BreakGlassActiveResponse(projectId, environment, grant != null, grant, now.atOffset(ZoneOffset.UTC));
	}

	/** 사후 감사용 이력. 조직 멤버면 역할과 관계없이 볼 수 있다. environment가 없으면 프로젝트의 모든 환경을 최근 발급 순으로 돌려준다. */
	@Transactional(readOnly = true)
	public List<BreakGlassGrantResponse> getHistory(Authentication authentication, String projectId, String environment,
			Integer limit, LocalDateTime now) {
		String organizationId = policyAccess.authorize(authentication, projectId, false).project().getOrganizationId();
		PageRequest page = PageRequest.of(0, normalizeLimit(limit));
		List<BreakGlassGrant> grants = environment == null || environment.isBlank()
				? grantRepository.findByOrganizationIdAndProjectIdOrderByIssuedAtDesc(organizationId, projectId, page)
				: grantRepository.findByOrganizationIdAndProjectIdAndEnvironmentOrderByIssuedAtDesc(
						organizationId, projectId, environment, page);
		return grants.stream().map(grant -> BreakGlassGrantResponse.of(grant, now)).toList();
	}

	// 조직 멤버인지는 PolicyAccess가 확인하고(아니면 404), 발급·취소 가능한 역할인지는 여기서 확인한다(아니면 403).
	private PolicyAccess.Grant requireOperator(Authentication authentication, String projectId) {
		PolicyAccess.Grant access = policyAccess.authorize(authentication, projectId, false);
		String role = membershipRepository
				.findByOrganizationIdAndUserId(access.project().getOrganizationId(), access.memberId())
				.map(membership -> membership.getRole().toUpperCase(Locale.ROOT))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!OPERATE_ROLES.contains(role)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "break-glass 발급·취소는 OWNER, ADMIN, OPERATOR만 할 수 있습니다.");
		}
		return access;
	}

	private void requireEnvironment(String projectId, String environment) {
		boolean exists = environmentRepository.findByProjectId(projectId).stream()
				.anyMatch(env -> env.getEnvironment().equals(environment));
		if (!exists) {
			throw new ApiException(ErrorCode.NOT_FOUND, "프로젝트에 해당 환경이 없습니다. environment=" + environment);
		}
	}

	private int normalizeLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_HISTORY_LIMIT;
		}
		if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "limit은 1~" + MAX_HISTORY_LIMIT + " 사이여야 합니다.");
		}
		return limit;
	}

	// 기존 운영 이벤트 피드(/api/events)에서도 발급·취소가 보이도록 남긴다.
	private void recordEvent(BreakGlassGrant grant, String type, String code, String message, String actor,
			String reason, LocalDateTime now) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("grantId", grant.getId());
		metadata.put("actor", actor);
		metadata.put("reason", reason);
		metadata.put("expiresAt", grant.getExpiresAt().atOffset(ZoneOffset.UTC).toString());
		eventRepository.save(OperationalEvent.builder()
				.organizationId(grant.getOrganizationId())
				.projectId(grant.getProjectId())
				.environment(grant.getEnvironment())
				.eventType(type)
				.eventCode(code)
				.message(message)
				.metadataJson(toJson(metadata))
				.occurredAt(now)
				.build());
	}

	private String toJson(Map<String, Object> metadata) {
		try {
			return objectMapper.writeValueAsString(metadata);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("break-glass 감사 메타데이터를 직렬화하지 못했습니다.", exception);
		}
	}
}
