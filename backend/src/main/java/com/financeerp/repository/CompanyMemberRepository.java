package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class CompanyMemberRepository {
	private final JdbcTemplate jdbc;

	public CompanyMemberRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int countOwnerMemberships(long companyId, long userId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role='OWNER'",
				Integer.class, companyId, userId);
		return count == null ? 0 : count;
	}

	public List<Map<String, Object>> findMembers(long companyId) {
		return jdbc.queryForList(
				"SELECT u.id AS \"userId\",u.email,u.display_name AS \"displayName\",m.role,m.created_at AS \"joinedAt\" FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? ORDER BY m.created_at",
				companyId);
	}

	public List<Map<String, Object>> findActiveUsersByEmail(String email) {
		return jdbc.queryForList(
				"SELECT id,email,display_name AS \"displayName\" FROM app_users WHERE lower(email)=lower(?) AND active=true",
				email);
	}

	public void insertMembership(long companyId, long userId, String role) {
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?,?)", companyId, userId,
				role);
	}

	public Map<String, Object> findMember(long companyId, long userId) {
		return jdbc.queryForMap(
				"SELECT u.id AS \"userId\",u.email,u.display_name AS \"displayName\",m.role,m.created_at AS \"joinedAt\" FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? AND m.user_id=?",
				companyId, userId);
	}
}
