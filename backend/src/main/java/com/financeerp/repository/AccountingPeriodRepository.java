package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AccountingPeriodRepository {
	private final JdbcTemplate jdbc;

	public AccountingPeriodRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findByCompany(long companyId) {
		return jdbc.queryForList(
				"SELECT id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\" FROM accounting_periods WHERE company_id=? ORDER BY start_date",
				companyId);
	}

	public int countOwnerOrApproverMemberships(long companyId, long userId) {
		Integer allowed = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role IN ('OWNER','APPROVER')",
				Integer.class, companyId, userId);
		return allowed == null ? 0 : allowed;
	}

	public void lockCompany(long companyId) {
		jdbc.queryForObject("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, companyId);
	}

	public int countOverlapping(long companyId, LocalDate start, LocalDate end) {
		Integer overlaps = jdbc.queryForObject(
				"SELECT count(*) FROM accounting_periods WHERE company_id=? AND daterange(start_date,end_date,'[]') && daterange(?,?,'[]')",
				Integer.class, companyId, start, end);
		return overlaps == null ? 0 : overlaps;
	}

	public Map<String, Object> insertPeriod(long companyId, String name, LocalDate start, LocalDate end) {
		return jdbc.queryForMap(
				"INSERT INTO accounting_periods(company_id,name,start_date,end_date) VALUES (?,?,?,?) RETURNING id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\"",
				companyId, name, start, end);
	}

	public Optional<Map<String, Object>> lockPeriod(long companyId, long periodId) {
		return jdbc.queryForList(
				"SELECT id,start_date AS \"startDate\",end_date AS \"endDate\",status FROM accounting_periods WHERE company_id=? AND id=? FOR UPDATE",
				companyId, periodId).stream().findFirst();
	}

	public int countUnpostedJournals(long companyId, Object startDate, Object endDate) {
		Integer drafts = jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND status IN ('DRAFT','SUBMITTED','APPROVED') AND journal_date BETWEEN ? AND ?",
				Integer.class, companyId, startDate, endDate);
		return drafts == null ? 0 : drafts;
	}

	public void markClosed(long companyId, long periodId, long closedByUserId) {
		jdbc.update(
				"UPDATE accounting_periods SET status='CLOSED',closed_at=now(),closed_by=? WHERE company_id=? AND id=?",
				closedByUserId, companyId, periodId);
	}

	public Map<String, Object> findPeriod(long companyId, long periodId) {
		return jdbc.queryForMap(
				"SELECT id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\" FROM accounting_periods WHERE company_id=? AND id=?",
				companyId, periodId);
	}
}
