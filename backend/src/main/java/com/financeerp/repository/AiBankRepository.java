package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Bank statement lines, candidate payments and accounts used by the AI bank assistant. */
@Repository
public class AiBankRepository {
	private static final String SIGNED = "CASE p.direction WHEN 'RECEIPT' THEN p.amount ELSE -p.amount END";
	private static final int MAX_LINES = 25;
	private static final String UNEXPLAINED_LINES = """
			SELECT s.id, s.txn_date AS "date", s.description, s.amount, s.reference
			FROM bank_statement_lines s
			WHERE s.company_id=? AND s.bank_account_id=? AND s.matched_payment_id IS NULL AND s.journal_entry_id IS NULL
			  AND NOT EXISTS (SELECT 1 FROM ai_proposals ap WHERE ap.company_id=s.company_id AND ap.status='PROPOSED'
			                  AND ap.payload->>'statementLineId' = s.id::text)
			  AND NOT EXISTS (SELECT 1 FROM payments p
			                  JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED'
			                  WHERE p.company_id=s.company_id AND p.bank_account_id=s.bank_account_id AND (%s)=s.amount
			                    AND p.payment_date BETWEEN s.txn_date-30 AND s.txn_date+30
			                    AND NOT EXISTS (SELECT 1 FROM bank_statement_lines m WHERE m.company_id=p.company_id AND m.matched_payment_id=p.id))
			ORDER BY s.txn_date, s.id LIMIT %d
			""".formatted(SIGNED, MAX_LINES);
	private static final String CANDIDATES = "SELECT p.id AS \"paymentId\", p.direction, p.payment_date AS \"paymentDate\", p.reference, "
			+ SIGNED + " AS amount FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED' "
			+ "WHERE p.company_id=? AND p.bank_account_id=? AND p.payment_date BETWEEN ? AND ? "
			+ "AND NOT EXISTS (SELECT 1 FROM bank_statement_lines s WHERE s.company_id=p.company_id AND s.matched_payment_id=p.id)";
	private final JdbcTemplate jdbc;

	public AiBankRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** At most 25 lines that have no payment match, journal, open proposal or obvious payment counterpart. */
	public List<Map<String, Object>> findUnexplainedLines(long companyId, long bankId) {
		return jdbc.queryForList(UNEXPLAINED_LINES, companyId, bankId);
	}

	public Map<String, Object> getAccount(long companyId, long accountId) {
		return jdbc.queryForMap("SELECT id,code,name FROM accounts WHERE company_id=? AND id=?", companyId, accountId);
	}

	public List<Map<String, Object>> findChartExcluding(long companyId, long excludedAccountId) {
		return jdbc.queryForList(
				"SELECT id,code,name,account_type AS type FROM accounts WHERE company_id=? AND active=true AND id<>? ORDER BY code LIMIT 300",
				companyId, excludedAccountId);
	}

	public List<Map<String, Object>> findOpenStatementLines(long companyId, long bankId) {
		return jdbc.queryForList(
				"SELECT id,txn_date AS \"date\",description,amount,reference FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL ORDER BY txn_date,id LIMIT 30",
				companyId, bankId);
	}

	public List<Map<String, Object>> findExactAmountCandidates(long companyId, long bankId, LocalDate from, LocalDate to,
			BigDecimal amount) {
		return jdbc.queryForList(CANDIDATES + " AND (" + SIGNED + ")=? ORDER BY p.payment_date", companyId, bankId, from, to,
				amount);
	}

	public List<Map<String, Object>> findNearAmountCandidates(long companyId, long bankId, LocalDate from, LocalDate to,
			BigDecimal low, BigDecimal high, BigDecimal excludedAmount) {
		return jdbc.queryForList(CANDIDATES + " AND (" + SIGNED + ") BETWEEN ? AND ? AND (" + SIGNED
				+ ")<>? ORDER BY p.payment_date LIMIT 3", companyId, bankId, from, to, low, high, excludedAmount);
	}
}
