package com.financeerp.report;

import com.financeerp.security.CompanyAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/reports")
public class ReportController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public ReportController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@GetMapping("/trial-balance")
	public List<Map<String, Object>> trialBalance(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		return jdbc.queryForList("""
				SELECT a.id AS "accountId", a.code, a.name, a.account_type AS "accountType",
				       COALESCE(SUM(l.debit),0) AS "debitTotal",
				       COALESCE(SUM(l.credit),0) AS "creditTotal",
				       COALESCE(SUM(l.debit-l.credit),0) AS "netDebit"
				FROM accounts a
				LEFT JOIN (
				    SELECT jl.company_id, jl.account_id, jl.debit, jl.credit
				    FROM journal_lines jl
				    JOIN journal_entries je ON je.company_id=jl.company_id AND je.id=jl.journal_entry_id
				    WHERE je.status='POSTED' AND je.journal_date BETWEEN ? AND ?
				) l ON l.company_id=a.company_id AND l.account_id=a.id
				WHERE a.company_id=?
				GROUP BY a.id,a.code,a.name,a.account_type ORDER BY a.code
				""", from, to, companyId);
	}
}
