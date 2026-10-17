package com.financeerp.period;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/periods")
public class AccountingPeriodController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public AccountingPeriodController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record CreatePeriodRequest(@NotBlank String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(
				"SELECT id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\" FROM accounting_periods WHERE company_id=? ORDER BY start_date",
				companyId);
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreatePeriodRequest req) {
		long userId = access.requireMembership(companyId);
		requireOwnerOrApprover(companyId, userId);
		if (req.startDate().isAfter(req.endDate()))
			throw new IllegalArgumentException("startDate must be on or before endDate.");
		// Serialize period creation per company so concurrent requests cannot create
		// overlapping periods.
		jdbc.queryForObject("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, companyId);
		Integer overlaps = jdbc.queryForObject(
				"SELECT count(*) FROM accounting_periods WHERE company_id=? AND daterange(start_date,end_date,'[]') && daterange(?,?,'[]')",
				Integer.class, companyId, req.startDate(), req.endDate());
		if (overlaps != null && overlaps > 0)
			throw new IllegalArgumentException("Accounting periods cannot overlap.");
		return jdbc.queryForMap(
				"INSERT INTO accounting_periods(company_id,name,start_date,end_date) VALUES (?,?,?,?) RETURNING id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\"",
				companyId, req.name().trim(), req.startDate(), req.endDate());
	}

	@PostMapping("/{periodId}/close")
	@Transactional
	public Map<String, Object> close(@PathVariable long companyId, @PathVariable long periodId) {
		long userId = access.requireMembership(companyId);
		requireOwnerOrApprover(companyId, userId);
		Map<String, Object> period;
		try {
			period = jdbc.queryForMap(
					"SELECT id,start_date AS \"startDate\",end_date AS \"endDate\",status FROM accounting_periods WHERE company_id=? AND id=? FOR UPDATE",
					companyId, periodId);
		} catch (org.springframework.dao.EmptyResultDataAccessException ex) {
			throw new IllegalArgumentException("Accounting period not found.");
		}
		if ("CLOSED".equals(period.get("status")))
			throw new IllegalArgumentException("Accounting period is already closed.");
		Integer drafts = jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND status IN ('DRAFT','SUBMITTED','APPROVED') AND journal_date BETWEEN ? AND ?",
				Integer.class, companyId, period.get("startDate"), period.get("endDate"));
		if (drafts != null && drafts > 0)
			throw new IllegalArgumentException(
					"Cannot close a period while it contains unposted journals. Complete their approval/posting workflow first.");
		jdbc.update(
				"UPDATE accounting_periods SET status='CLOSED',closed_at=now(),closed_by=? WHERE company_id=? AND id=?",
				userId, companyId, periodId);
		return jdbc.queryForMap(
				"SELECT id,name,start_date AS \"startDate\",end_date AS \"endDate\",status,closed_at AS \"closedAt\" FROM accounting_periods WHERE company_id=? AND id=?",
				companyId, periodId);
	}

	private void requireOwnerOrApprover(long companyId, long userId) {
		Integer allowed = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role IN ('OWNER','APPROVER')",
				Integer.class, companyId, userId);
		if (allowed == null || allowed == 0)
			throw new org.springframework.security.access.AccessDeniedException(
					"Only a company owner or approver can manage accounting periods.");
	}
}
