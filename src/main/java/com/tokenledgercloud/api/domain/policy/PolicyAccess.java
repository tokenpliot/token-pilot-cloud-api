package com.tokenledgercloud.api.domain.policy;

import java.util.Locale;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.domain.project.entity.Project;
import com.tokenledgercloud.api.domain.project.entity.ProjectStatus;
import com.tokenledgercloud.api.domain.project.repository.ProjectRepository;
import com.tokenledgercloud.api.global.exception.ApiException;
import com.tokenledgercloud.api.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PolicyAccess {
	private final MemberRepository members;
	private final OrganizationMembershipRepository memberships;
	private final ProjectRepository projects;
	private final EntityManager entityManager;

	public Grant authorize(Authentication authentication, String projectId, boolean write) {
		Member member = member(authentication);
		Project project = projects.findById(projectId).orElseThrow(PolicyAccess::hidden);
		OrganizationMembership membership = memberships.findByOrganizationIdAndUserId(project.getOrganizationId(), member.getId())
			.orElseThrow(PolicyAccess::hidden);
		String role = membership.getRole().toUpperCase(Locale.ROOT);
		if (!Set.of("OWNER", "ADMIN", "OPERATOR", "VIEWER").contains(role)
			|| (write && !Set.of("OWNER", "ADMIN").contains(role))) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (write) {
			// One lock serializes creation, revision changes, legacy imports and request-context capture.
			String organizationId = project.getOrganizationId();
			// A locking query alone reuses the managed entity loaded above; refresh also reloads its status.
			entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
			if (!organizationId.equals(project.getOrganizationId())) throw hidden();
			if (project.getStatus() != ProjectStatus.ACTIVE) {
				throw new ApiException(ErrorCode.CONFLICT, "Only active projects can change policies.");
			}
		}
		return new Grant(project, member.getId());
	}

	private Member member(Authentication auth) {
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			throw new ApiException(ErrorCode.UNAUTHORIZED);
		}
		if (auth instanceof OAuth2AuthenticationToken oauth) {
			return members.findByProviderAndProviderId(oauth.getAuthorizedClientRegistrationId(), auth.getName())
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		}
		if (auth.getPrincipal() instanceof UserDetails) {
			return members.findByEmail(auth.getName()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		}
		throw new ApiException(ErrorCode.UNSUPPORTED_AUTHENTICATION);
	}

	private static ApiException hidden() { return new ApiException(ErrorCode.NOT_FOUND); }
	public record Grant(Project project, String memberId) {}
}
