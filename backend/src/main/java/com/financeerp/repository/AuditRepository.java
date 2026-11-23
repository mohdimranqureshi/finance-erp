package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit_events writes shared by every feature. */
@Repository
public class AuditRepository {
	private final JdbcTemplate jdbc;

	public AuditRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insertCompanyEvent(long companyId, String actor, String action, String entityType, long entityId,
			String detail) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?,?,?,?,jsonb_build_object('detail',?))",
				companyId, actor, action, entityType, String.valueOf(entityId), detail);
	}

	/** Global (company_id NULL) event about a USER; detailsJson must already be a JSON object. */
	public void insertUserEvent(String actor, String action, String userId, String detailsJson) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,?,'USER',?,CAST(? AS jsonb))",
				actor, action, userId, detailsJson);
	}
}