package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class CompanyInvitationRepository {
	private final JdbcTemplate jdbc;

	public CompanyInvitationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int countOwnerMemberships(long companyId, long userId) {
		Integer isOwner = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role='OWNER'",
				Integer.class, companyId, userId);
		return isOwner == null ? 0 : isOwner;
	}

	public int countUsersByEmail(String email) {
		Integer existing = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",
				Integer.class, email);
		return existing == null ? 0 : existing;
	}

	public void deleteExpiredPending(long companyId, String email) {
		jdbc.update(
				"DELETE FROM company_invitations WHERE company_id=? AND lower(email)=lower(?) AND accepted_at IS NULL AND expires_at<=now()",
				companyId, email);
	}

	public void insertInvitation(long companyId, String email, String role, String tokenHash, OffsetDateTime expiry,
			long createdByUserId) {
		jdbc.update(
				"INSERT INTO company_invitations(company_id,email,role,token_hash,expires_at,created_by_user_id) VALUES (?,?,?,?,?,?)",
				companyId, email, role, tokenHash, expiry, createdByUserId);
	}

	public void insertInvitedAuditEvent(long companyId, String email, String entityId, String role) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?, 'MEMBER_INVITED','COMPANY_INVITATION',?,jsonb_build_object('email',?,'role',?))",
				companyId, email, entityId, email, role);
	}

	public List<Map<String, Object>> lockPendingByTokenHash(String tokenHash) {
		return jdbc.query(
				"SELECT id,company_id,email,role,expires_at FROM company_invitations WHERE token_hash=? AND accepted_at IS NULL FOR UPDATE",
				(rs, row) -> Map.<String, Object>of("id", rs.getLong("id"), "companyId", rs.getLong("company_id"),
						"email", rs.getString("email"), "role", rs.getString("role"), "expiresAt",
						rs.getObject("expires_at", OffsetDateTime.class)),
				tokenHash);
	}

	public Long insertUser(String email, String passwordHash, String displayName) {
		return jdbc.queryForObject("INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?) RETURNING id",
				Long.class, email, passwordHash, displayName);
	}

	public void insertMembership(long companyId, Long userId, Object role) {
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?,?)", companyId, userId,
				role);
	}

	public void markAccepted(Long userId, Object invitationId) {
		jdbc.update("UPDATE company_invitations SET accepted_at=now(),accepted_by_user_id=? WHERE id=?", userId,
				invitationId);
	}

	public void insertAcceptedAuditEvent(long companyId, String email, String entityId, Object role) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?, 'MEMBER_INVITATION_ACCEPTED','COMPANY_INVITATION',?,jsonb_build_object('email',?,'role',?))",
				companyId, email, entityId, email, role);
	}
}
