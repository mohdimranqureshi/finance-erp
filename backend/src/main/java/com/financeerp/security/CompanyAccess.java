package com.financeerp.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CompanyAccess {
	private final JdbcTemplate jdbc;

	public CompanyAccess(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long requireMembership(long companyId) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
			throw new org.springframework.security.access.AccessDeniedException("Authentication required.");
		var rows = jdbc.query(
				"SELECT u.id FROM app_users u JOIN company_memberships m ON m.user_id=u.id WHERE lower(u.email)=lower(?) AND m.company_id=? AND u.active=true",
				(rs, row) -> rs.getLong(1), auth.getName(), companyId);
		if (rows.isEmpty()) {
			Integer exists = jdbc.queryForObject("SELECT count(*) FROM companies WHERE id=?", Integer.class, companyId);
			if (exists == null || exists == 0)
				throw new IllegalArgumentException("Company not found.");
			throw new org.springframework.security.access.AccessDeniedException(
					"You do not have access to this company.");
		}
		return rows.get(0);
	}

	public long requireRole(long companyId, java.util.Set<String> allowedRoles) {
		long userId = requireMembership(companyId);
		String placeholders = String.join(",", java.util.Collections.nCopies(allowedRoles.size(), "?"));
		java.util.List<String> roles = jdbc.query(
				"SELECT m.role FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? AND u.id=? AND m.role IN ("
						+ placeholders + ")",
				(rs, row) -> rs.getString(1), buildArgs(companyId, userId, allowedRoles));
		if (roles.isEmpty())
			throw new org.springframework.security.access.AccessDeniedException(
					"Your company role does not permit this action.");
		return userId;
	}

	private Object[] buildArgs(long companyId, long userId, java.util.Set<String> roles) {
		java.util.List<Object> args = new java.util.ArrayList<>();
		args.add(companyId);
		args.add(userId);
		args.addAll(roles);
		return args.toArray();
	}

	public long currentUserId() {
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		Long id = jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?) AND active=true",
				Long.class, email);
		if (id == null)
			throw new org.springframework.security.access.AccessDeniedException("Active user not found.");
		return id;
	}
}
