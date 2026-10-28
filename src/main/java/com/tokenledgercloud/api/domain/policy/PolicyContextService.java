package com.tokenledgercloud.api.domain.policy;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshot;
import com.tokenledgercloud.api.domain.accounting.snapshot.PricingSnapshotRepository;
import com.tokenledgercloud.api.domain.ingestion.service.AuthenticatedProjectApiKey;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PolicyContextService {
	private final PolicyAccess access;
	private final ProjectRepository projects;
	private final CustomerPolicyRepository policies;
	private final PolicySnapshotRepository snapshots;
	private final PricingSnapshotRepository prices;
	private final PolicyRequestContextRepository contexts;
	private final EntityManager entityManager;

	/** Server-side integration seam for #15. Never accept these snapshot IDs directly from an SDK payload. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ContextView capture(AuthenticatedProjectApiKey key, String environment, String requestId, String pricingSnapshotId) {
		validateRequest(environment, requestId);
		if (key == null || key.apiKeyId() == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
		if (key.environment() != null && !key.environment().isBlank() && !key.environment().equals(environment)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		var project = projects.lockByIdAndOrganizationId(key.projectId(), key.organizationId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// The caller may already have loaded this project into the persistence context during authentication.
		entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
		if (!key.organizationId().equals(project.getOrganizationId())) throw new ApiException(ErrorCode.NOT_FOUND);
		if (project.getStatus() != ProjectStatus.ACTIVE) throw new ApiException(ErrorCode.FORBIDDEN);
		var existing = contexts.findByOrganizationIdAndProjectIdAndEnvironmentAndRequestId(
			key.organizationId(), key.projectId(), environment, requestId);
		// A retry reuses its original binding, even after a price/policy update. #15 checks input fingerprints.
		if (existing.isPresent()) return view(existing.get());
		if (pricingSnapshotId != null) prices.findById(pricingSnapshotId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		var context = PolicyRequestContext.capture(key.organizationId(), key.projectId(), environment, requestId,
			current(key.organizationId(), key.projectId(), ""), current(key.organizationId(), key.projectId(), environment),
			pricingSnapshotId, key.apiKeyId());
		return view(contexts.save(context));
	}

	@Transactional(readOnly = true)
	public ContextView explain(Authentication auth, String projectId, String environment, String requestId) {
		validateRequest(environment, requestId);
		var grant = access.authorize(auth, projectId, false);
		return view(contexts.findByOrganizationIdAndProjectIdAndEnvironmentAndRequestId(
			grant.project().getOrganizationId(), projectId, environment, requestId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	private PolicySnapshot current(String org, String project, String environmentKey) {
		return policies.lockByScope(org, project, environmentKey)
			.map(p -> snapshots.findByPolicyIdAndRevision(p.getId(), p.getCurrentVersion()).orElseThrow())
			.orElse(null);
	}

	private ContextView view(PolicyRequestContext context) {
		List<PolicyVersionView> selected = Stream.of(context.getProjectPolicySnapshotId(), context.getEnvironmentPolicySnapshotId())
			.filter(Objects::nonNull).map(id -> PolicyVersionView.from(snapshots.findById(id).orElseThrow())).toList();
		PricingSnapshot price = context.getPricingSnapshotId() == null ? null : prices.findById(context.getPricingSnapshotId()).orElseThrow();
		return new ContextView(context, selected, price == null ? null : new PriceView(price.getId(), price.getVersion(),
			price.getChecksum(), price.getSnapshotJson()), price == null ? "UNAVAILABLE" : "AVAILABLE");
	}

	private void validateRequest(String environment, String requestId) {
		PolicyService.validateEnvironment(environment);
		if (environment == null || requestId == null || requestId.isBlank() || requestId.length() > 100 || !requestId.equals(requestId.strip())) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

	public record PriceView(String snapshotId, String version, String checksum, String snapshotJson) {}
	public record ContextView(PolicyRequestContext context, List<PolicyVersionView> policies, PriceView price, String priceStatus) {}
}
