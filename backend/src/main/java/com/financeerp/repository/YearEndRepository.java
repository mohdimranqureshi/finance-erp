package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class YearEndRepository {
	private final JdbcTemplate jdbc;

	public YearEndRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> lockPeriod(long companyId, Long periodId) {
		return jdbc.queryForList(
				"SELECT start_date AS start,end_date AS \"end\",status FROM accounting_periods WHERE company_id=? AND id=? FOR UPDATE",
				companyId, periodId);
	}

	public int countUnpostedJournals(long companyId, LocalDate start, LocalDate end) {
		Integer pending = jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND journal_date BETWEEN ? AND ? AND status IN ('DRAFT','SUBMITTED','APPROVED')",
				Integer.class, companyId, start, end);
		return pending == null ? 0 : pending;
	}

	public List<Map<String, Object>> findProfitLossNets(long companyId, LocalDate start, LocalDate end) {
		return jdbc.queryForList("""
				SELECT a.id AS account,SUM(l.credit-l.debit) AS net
				FROM accounts a
				JOIN journal_lines l ON l.company_id=a.company_id AND l.account_id=a.id
				JOIN journal_entries j ON j.company_id=l.company_id AND j.id=l.journal_entry_id
				WHERE a.company_id=? AND a.account_type IN ('INCOME','EXPENSE') AND j.status='POSTED'
				  AND NOT j.is_closing AND j.journal_date BETWEEN ? AND ?
				GROUP BY a.id HAVING SUM(l.credit-l.debit) <> 0 ORDER BY a.id
				""", companyId, start, end);
	}

	public void markClosingJournal(long companyId, long journalId) {
		jdbc.update("UPDATE journal_entries SET is_closing=true WHERE company_id=? AND id=?", companyId, journalId);
	}
}
