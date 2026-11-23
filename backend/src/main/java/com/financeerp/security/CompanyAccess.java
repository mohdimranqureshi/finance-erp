package com.financeerp.security;

import com.financeerp.repository.MembershipRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

@Component
public class CompanyAccess {
	private final MembershipRepository memberships;

	public CompanyAccess(MembershipRepository memberships) {
		this.memberships = memberships;
	}

	public long requireMembership(long companyId) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
			throw new AccessDeniedException("Authentication required.");
		List<Long> rows = memberships.findActiveMemberUserIds(auth.getName(), companyId);
		if (rows.isEmpty()) {
			if (!memberships.companyExists(companyId))
				throw new IllegalArgumentException("Company not found.");
			throw new AccessDeniedException("You do not have access to this company.");
		}
		return rows.get(0);
	}

	public long requireRole(long companyId, Set<String> allowedRoles) {
		long userId = requireMembership(companyId);
		if (!memberships.hasAnyRole(companyId, userId, allowedRoles))
			throw new AccessDeniedException("Your company role does not permit this action.");
		return userId;
	}

	public long currentUserId() {
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		Long id = memberships.findActiveUserId(email);
		if (id == null)
			throw new AccessDeniedException("Active user not found.");
		return id;
	}
}