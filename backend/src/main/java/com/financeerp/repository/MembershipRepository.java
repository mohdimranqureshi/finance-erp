package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Who may act in which company. */
@Repository
public class MembershipRepository {
	private final JdbcTemplate jdbc;

	public MembershipRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Long> findActiveMemberUserIds(String email, long companyId) {
		return jdbc.query(
				"SELECT u.id FROM app_users u JOIN company_memberships m ON m.user_id=u.id WHERE lower(u.email)=lower(?) AND m.company_id=? AND u.active=true",
				(rs, row) -> rs.getLong(1), email, companyId);
	}

	public boolean companyExists(long companyId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM companies WHERE id=?", Integer.class, companyId);
		return n != null && n > 0;
	}

	public boolean hasAnyRole(long companyId, long userId, Set<String> roles) {
		String placeholders = String.join(",", Collections.nCopies(roles.size(), "?"));
		List<Object> args = new ArrayList<>();
		args.add(companyId);
		args.add(userId);
		args.addAll(roles);
		List<String> found = jdbc.query(
				"SELECT m.role FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? AND u.id=? AND m.role IN ("
						+ placeholders + ")",
				(rs, row) -> rs.getString(1), args.toArray());
		return !found.isEmpty();
	}

	/** Null when the email has no active account. */
	public Long findActiveUserId(String email) {
		List<Long> ids = jdbc.query("SELECT id FROM app_users WHERE lower(email)=lower(?) AND active=true",
				(rs, row) -> rs.getLong(1), email);
		return ids.isEmpty() ? null : ids.get(0);
	}
}