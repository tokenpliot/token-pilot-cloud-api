package com.tokenledgercloud.api.domain.policy;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tokenledgercloud.api.domain.budget.entity.MonthlyBudgetSetting;
import com.tokenledgercloud.api.domain.budget.repository.MonthlyBudgetSettingRepository;
import com.tokenledgercloud.api.domain.budget.service.BudgetCalculator;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectEnvironment;
import com.tokenledgercloud.api.domain.project.repository.ProjectEnvironmentRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PolicyService {
	private final PolicyAccess access;
	private final CustomerPolicyRepository policies;
	private final PolicySnapshotRepository snapshots;
	private final MonthlyBudgetSettingRepository budgets;
	private final BudgetCalculator budgetCalculator;
	private final ProjectEnvironmentRepository environments;

	@Transactional
	public PolicyVersionView replace(Authentication auth, String projectId, String environment, PolicyRequests.Replace request) {
		var grant = access.authorize(auth, projectId, true);
		validateScope(grant.project(), environment);
		CustomerPolicy policy = policy(grant.project(), environment, true);
		expected(policy, request.expectedVersion());
		return append(grant, policy, request.rules(), request.reason(), policy.getCurrentVersion() == 0 ? "CREATE" : "REPLACE", null);
	}

	@Transactional
	public PolicyVersionView importBudget(Authentication auth, String projectId, String environment, PolicyRequests.ImportBudget request) {
		var grant = access.authorize(auth, projectId, true);
		validateScope(grant.project(), environment);
		CustomerPolicy policy = policy(grant.project(), environment, true);
		expected(policy, 0L);
		MonthlyBudgetSetting budget = existingBudget(policy);
		if (budget == null) throw new ApiException(ErrorCode.BUDGET_NOT_FOUND);
		// Legacy notification thresholds are NOT soft limits and import never enables enforcement.
		PolicyRules rules = new PolicyRules("USD", null, budget.getLimitUsd(), null, null, true, budgetCalculator.toThresholds(budget));
		return append(grant, policy, rules, request.reason(), "IMPORT_LEGACY", null);
	}

	@Transactional
	public PolicyVersionView rollback(Authentication auth, String projectId, String environment, PolicyRequests.Rollback request) {
		var grant = access.authorize(auth, projectId, true);
		// Historical scopes remain readable; a restore still needs a currently registered environment.
		validateScope(grant.project(), environment);
		CustomerPolicy policy = policy(grant.project(), environment, true);
		expected(policy, request.expectedVersion());
		PolicySnapshot restored = version(policy, request.restoreVersion());
		return append(grant, policy, restored.rules(), request.reason(), "ROLLBACK", restored.getRevision());
	}

	@Transactional(readOnly = true)
	public PolicyVersionView current(Authentication auth, String projectId, String environment) {
		var grant = access.authorize(auth, projectId, false);
		CustomerPolicy policy = requirePolicy(grant.project(), environment);
		return PolicyVersionView.from(version(policy, policy.getCurrentVersion()));
	}

	@Transactional(readOnly = true)
	public PolicyVersionView version(Authentication auth, String projectId, String environment, long revision) {
		var grant = access.authorize(auth, projectId, false);
		return PolicyVersionView.from(version(requirePolicy(grant.project(), environment), revision));
	}

	@Transactional(readOnly = true)
	public List<PolicyVersionView> history(Authentication auth, String projectId, String environment, long beforeVersion, int limit) {
		if (beforeVersion < 1 || limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_INPUT);
		var grant = access.authorize(auth, projectId, false);
		CustomerPolicy policy = requirePolicy(grant.project(), environment);
		return snapshots.findByPolicyIdAndRevisionLessThanOrderByRevisionDesc(policy.getId(), beforeVersion, PageRequest.ofSize(limit))
			.stream().map(PolicyVersionView::from).toList();
	}

	@Transactional(readOnly = true)
	public Impact preview(Authentication auth, String projectId, String environment, PolicyRequests.Replace request) {
		var grant = access.authorize(auth, projectId, false);
		validateScope(grant.project(), environment);
		CustomerPolicy policy = policy(grant.project(), environment, false);
		expected(policy, request.expectedVersion());
		PolicyVersionView previous = policy.getCurrentVersion() == 0 ? null : PolicyVersionView.from(version(policy, policy.getCurrentVersion()));
		List<String> affected = environment == null ? environments.findByProjectId(projectId).stream()
			.filter(e -> e.getOrganizationId().equals(grant.project().getOrganizationId()))
			.map(ProjectEnvironment::getEnvironment).sorted().toList() : List.of(environment);
		return new Impact(policy.getCurrentVersion(), previous, request.rules(), affected,
			environment == null, "STRUCTURAL_ONLY", "ALL_APPLICABLE_SCOPES",
			"New requests use the new revision. Captured requests retain their snapshots. No automatic enforcement.");
	}

	private PolicyVersionView append(PolicyAccess.Grant grant, CustomerPolicy policy, PolicyRules rules,
		String reason, String changeType, Long restoredFrom) {
		if (rules == null || reason == null || reason.isBlank() || reason.length() > 500) throw new ApiException(ErrorCode.INVALID_INPUT);
		MonthlyBudgetSetting budget = existingBudget(policy);
		String sourceBudgetId = budget == null ? null : budget.getId();
		policy.advance();
		policies.saveAndFlush(policy);
		PolicySnapshot snapshot = snapshots.save(PolicySnapshot.capture(policy, rules, grant.memberId(), changeType, reason, restoredFrom, sourceBudgetId));
		// Preserve v0 response shape. The legacy table is now a projection, never the source for historical audits.
		if (budget == null) budget = MonthlyBudgetSetting.builder().organizationId(policy.getOrganizationId())
			.projectId(policy.getProjectId()).environment(policy.environment()).build();
		budget.setLimitUsd(rules.monthlyHardLimitUsd());
		budgetCalculator.applyThresholds(budget, rules.notificationThresholds());
		budget.setCurrency("USD");
		budget.setPolicyVersion(snapshot.getVersion());
		budget.setUpdatedByType("MEMBER");
		budget.setUpdatedById(grant.memberId());
		budgets.save(budget);
		return PolicyVersionView.from(snapshot);
	}

	private MonthlyBudgetSetting existingBudget(CustomerPolicy policy) {
		List<MonthlyBudgetSetting> matches = budgets.findAllByOrganizationIdAndProjectIdAndEnvironment(
			policy.getOrganizationId(), policy.getProjectId(), policy.environment());
		if (matches.size() > 1) throw new ApiException(ErrorCode.CONFLICT, "Duplicate legacy budgets must be reconciled before managing this scope.");
		if (!matches.isEmpty() && (!"USD".equals(matches.getFirst().getCurrency())
			|| !Objects.equals(matches.getFirst().getEnvironment(), policy.environment()))) {
			throw new ApiException(ErrorCode.CONFLICT, "Legacy budget currency or environment requires reconciliation.");
		}
		return matches.isEmpty() ? null : matches.getFirst();
	}

	private CustomerPolicy policy(Project project, String environment, boolean lock) {
		validateEnvironment(environment);
		// Locking reads see committed state under MySQL REPEATABLE READ even after membership reads.
		return (lock ? policies.lockByScope(project.getOrganizationId(), project.getId(), key(environment))
			: policies.findByOrganizationIdAndProjectIdAndEnvironmentKey(project.getOrganizationId(), project.getId(), key(environment)))
			.orElseGet(() -> CustomerPolicy.create(project.getOrganizationId(), project.getId(), environment));
	}

	private CustomerPolicy requirePolicy(Project project, String environment) {
		CustomerPolicy policy = policy(project, environment, false);
		if (policy.getCurrentVersion() == 0) throw new ApiException(ErrorCode.NOT_FOUND);
		return policy;
	}

	private PolicySnapshot version(CustomerPolicy policy, long version) {
		return snapshots.findByPolicyIdAndRevision(policy.getId(), version).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private void expected(CustomerPolicy policy, Long expected) {
		if (expected == null || expected < 0) throw new ApiException(ErrorCode.INVALID_INPUT);
		if (policy.getCurrentVersion() != expected) throw new ApiException(ErrorCode.CONFLICT, "Policy version changed. Read the current version and preview again.");
	}

	private void validateScope(Project project, String environment) {
		validateEnvironment(environment);
		if (environment != null && environments.findByProjectId(project.getId()).stream().noneMatch(e ->
			e.getEnvironment().equals(environment) && e.getOrganizationId().equals(project.getOrganizationId()))) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "Environment is not registered to this project.");
		}
	}

	static void validateEnvironment(String environment) {
		if (environment != null && (environment.isBlank() || environment.length() > 20 || !environment.equals(environment.strip()))) {
			throw new ApiException(ErrorCode.INVALID_INPUT, "Environment must be 1-20 characters without surrounding whitespace.");
		}
	}

	static String key(String environment) { return Objects.requireNonNullElse(environment, ""); }

	public record Impact(long baseVersion, PolicyVersionView previous, PolicyRules proposed,
		List<String> affectedRegisteredEnvironments, boolean includesFutureEnvironments, String estimateType,
		String composition, String effect) {}
}
