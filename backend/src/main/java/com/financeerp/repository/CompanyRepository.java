package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class CompanyRepository {
	private final JdbcTemplate jdbc;

	public CompanyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> insertCompany(String name, String legalName, String currency) {
		return jdbc.queryForMap(
				"INSERT INTO companies(name,legal_name,base_currency) VALUES (?,?,?) RETURNING id,name,legal_name AS \"legalName\",base_currency AS \"baseCurrency\",status,created_at AS \"createdAt\"",
				name, legalName, currency);
	}

	public void insertOwnerMembership(long companyId, long userId) {
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?, 'OWNER')", companyId,
				userId);
	}

	public void insertAccountingPeriod(long companyId, String name, LocalDate start, LocalDate end) {
		jdbc.update("INSERT INTO accounting_periods(company_id,name,start_date,end_date) VALUES (?,?,?,?)", companyId,
				name, start, end);
	}

	public List<Map<String, Object>> findByMemberEmail(String email) {
		return jdbc.queryForList(
				"SELECT c.id,c.name,c.legal_name AS \"legalName\",c.base_currency AS \"baseCurrency\",c.status,c.created_at AS \"createdAt\" FROM companies c JOIN company_memberships m ON m.company_id=c.id JOIN app_users u ON u.id=m.user_id WHERE lower(u.email)=lower(?) AND u.active=true ORDER BY c.id DESC",
				email);
	}

	public Map<String, Object> findSettings(long companyId) {
		return jdbc.queryForMap("SELECT allow_self_approval AS \"allowSelfApproval\" FROM companies WHERE id=?",
				companyId);
	}

	public void updateAllowSelfApproval(long companyId, Boolean allowSelfApproval) {
		jdbc.update("UPDATE companies SET allow_self_approval=? WHERE id=?", allowSelfApproval, companyId);
	}

	public void insertSettingsAuditEvent(long companyId, String actor, String action, Boolean allowSelfApproval) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?,?,'COMPANY',?,jsonb_build_object('allowSelfApproval',?))",
				companyId, actor, action, String.valueOf(companyId), allowSelfApproval);
	}
}
