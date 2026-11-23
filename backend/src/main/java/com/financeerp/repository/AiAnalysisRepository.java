package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AiAnalysisRepository {
	private static final String MONTHLY = """
			SELECT date_trunc('month', je.journal_date)::date AS mon, SUM(l.credit-l.debit) AS net
			FROM journal_lines l
			JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
			JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
			WHERE l.company_id=? AND je.status='POSTED' AND NOT je.is_closing AND a.account_type=? AND je.journal_date >= ?
			GROUP BY 1
			""";
	private static final String CASH = """
			SELECT COALESCE(SUM(l.debit-l.credit),0)
			FROM journal_lines l
			JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
			JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
			WHERE l.company_id=? AND je.status='POSTED' AND a.account_type='ASSET' AND (a.system_key='BANK' OR a.code='1000')
			""";
	private final JdbcTemplate jdbc;

	public AiAnalysisRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findVendorSpikes(long companyId) {
		return jdbc.queryForList("""
				SELECT p.id AS "vendorId", p.name AS vendor, cur.amt AS "thisMonth", round(prev.avg, 2) AS "monthlyAverage"
				FROM (SELECT vendor_id, SUM(total_amount) AS amt FROM purchase_bills
				      WHERE company_id=? AND status='BOOKED' AND bill_date >= date_trunc('month', current_date)
				      GROUP BY vendor_id) cur
				JOIN (SELECT vendor_id, SUM(total_amount)/3 AS avg FROM purchase_bills
				      WHERE company_id=? AND status='BOOKED' AND bill_date < date_trunc('month', current_date)
				        AND bill_date >= date_trunc('month', current_date) - interval '3 months'
				      GROUP BY vendor_id) prev ON prev.vendor_id=cur.vendor_id
				JOIN parties p ON p.company_id=? AND p.id=cur.vendor_id
				WHERE cur.amt > 2*prev.avg AND cur.amt - prev.avg > 10000
				ORDER BY cur.amt - prev.avg DESC LIMIT 10
				""", companyId, companyId, companyId);
	}

	public List<Map<String, Object>> findDuplicatePayments(long companyId) {
		return jdbc.queryForList("""
				SELECT a.id AS "paymentId", b.id AS "otherPaymentId", a.direction, a.amount,
				       a.payment_date AS "date", b.payment_date AS "otherDate"
				FROM payments a JOIN payments b ON b.company_id=a.company_id AND b.id>a.id AND b.direction=a.direction
				     AND b.bank_account_id=a.bank_account_id AND b.amount=a.amount AND abs(b.payment_date-a.payment_date)<=3
				WHERE a.company_id=? AND a.payment_date >= current_date - 90
				ORDER BY a.payment_date DESC LIMIT 10
				""", companyId);
	}

	public List<Map<String, Object>> findOutlierAmounts(long companyId) {
		return jdbc.queryForList("""
				WITH s AS (
				  SELECT l.account_id, l.journal_entry_id, GREATEST(l.debit,l.credit) AS amt, je.journal_date,
				         AVG(GREATEST(l.debit,l.credit)) OVER (PARTITION BY l.account_id) AS mean,
				         STDDEV_SAMP(GREATEST(l.debit,l.credit)) OVER (PARTITION BY l.account_id) AS sd,
				         COUNT(*) OVER (PARTITION BY l.account_id) AS n
				  FROM journal_lines l JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				  WHERE l.company_id=? AND je.status='POSTED' AND NOT je.is_closing)
				SELECT s.journal_entry_id AS "journalId", a.code, a.name AS account, s.amt AS amount,
				       s.journal_date AS "date", round(s.mean, 2) AS "typicalAmount"
				FROM s JOIN accounts a ON a.company_id=? AND a.id=s.account_id
				WHERE a.account_type IN ('INCOME','EXPENSE') AND s.n >= 8 AND s.sd > 0 AND s.amt > s.mean + 3*s.sd
				  AND s.journal_date >= current_date - 90
				ORDER BY s.amt DESC LIMIT 10
				""", companyId, companyId);
	}

	public List<Map<String, Object>> findBackdatedJournals(long companyId) {
		return jdbc.queryForList("""
				SELECT id AS "journalId", journal_date AS "date", created_at::date AS "createdOn", description
				FROM journal_entries
				WHERE company_id=? AND NOT is_closing AND created_at >= now() - interval '30 days'
				  AND created_at::date - journal_date > 30
				ORDER BY created_at DESC LIMIT 10
				""", companyId);
	}

	/** Month start -> net (credit minus debit) of posted lines on accounts of the given type. */
	public Map<LocalDate, BigDecimal> monthlyNet(long companyId, String accountType, LocalDate from) {
		Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
		jdbc.query(MONTHLY, rs -> {
			out.merge(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), BigDecimal::add);
		}, companyId, accountType, from);
		return out;
	}

	public List<Map<String, Object>> findFluxDrivers(long companyId, Object accountId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("""
				SELECT je.id AS "journalId", je.journal_date AS "date",
				       COALESCE(NULLIF(l.description,''), je.description) AS description, GREATEST(l.debit,l.credit) AS amount
				FROM journal_lines l JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				WHERE l.company_id=? AND l.account_id=? AND je.status='POSTED' AND NOT je.is_closing
				  AND je.journal_date BETWEEN ? AND ?
				ORDER BY GREATEST(l.debit,l.credit) DESC LIMIT 3
				""", companyId, accountId, from, to);
	}

	public List<Map<String, Object>> findPeriods(long companyId, Long periodId) {
		String select = "SELECT id,name,start_date AS start,end_date AS \"end\",status FROM accounting_periods WHERE company_id=? ";
		return periodId != null
				? jdbc.queryForList(select + "AND id=?", companyId, periodId)
				: jdbc.queryForList(select + "ORDER BY (status='OPEN') DESC, CASE WHEN status='OPEN' THEN start_date END, start_date DESC LIMIT 1", companyId);
	}

	public int countUnpostedJournals(long companyId, LocalDate start, LocalDate end) {
		return count("SELECT count(*) FROM journal_entries WHERE company_id=? AND NOT is_closing AND status IN ('DRAFT','SUBMITTED','APPROVED') AND journal_date BETWEEN ? AND ?",
				companyId, start, end);
	}

	public int countStatementLines(long companyId) {
		return count("SELECT count(*) FROM bank_statement_lines WHERE company_id=?", companyId);
	}

	public int countUnexplainedStatementLines(long companyId, LocalDate end) {
		return count("SELECT count(*) FROM bank_statement_lines WHERE company_id=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL AND txn_date<=?",
				companyId, end);
	}

	public int countDueRevenueEntries(long companyId, LocalDate end) {
		return count("""
				SELECT count(*) FROM revenue_schedule_entries e
				JOIN revenue_schedules s ON s.company_id=e.company_id AND s.id=e.schedule_id
				JOIN journal_entries d ON d.company_id=s.company_id AND d.id=s.deferral_journal_id AND d.status='POSTED'
				WHERE e.company_id=? AND e.journal_entry_id IS NULL AND e.recognition_date<=?
				""", companyId, end);
	}

	public int countDuplicateBills(long companyId, LocalDate start, LocalDate end) {
		return count("SELECT count(*) FROM purchase_bills a JOIN purchase_bills b ON a.company_id=b.company_id AND a.vendor_id=b.vendor_id AND a.id<b.id AND a.total_amount=b.total_amount AND abs(a.bill_date-b.bill_date)<=7 WHERE a.company_id=? AND a.bill_date BETWEEN ? AND ?",
				companyId, start, end);
	}

	public List<String> findClosingJournalStatuses(long companyId, LocalDate start, LocalDate end) {
		return jdbc.queryForList(
				"SELECT status FROM journal_entries WHERE company_id=? AND is_closing AND journal_date BETWEEN ? AND ? ORDER BY id DESC LIMIT 1",
				String.class, companyId, start, end);
	}

	public BigDecimal glBalance(long companyId, String systemKey) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l
				JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
				WHERE l.company_id=? AND a.system_key=? AND je.status='POSTED' AND je.journal_date<=current_date
				""", BigDecimal.class, companyId, systemKey);
	}

	public BigDecimal cashBalance(long companyId) {
		return jdbc.queryForObject(CASH, BigDecimal.class, companyId);
	}

	public List<Map<String, Object>> findTopVendorsLast90Days(long companyId) {
		return jdbc.queryForList("""
				SELECT p.name, SUM(b.total_amount) AS amount
				FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE b.company_id=? AND b.status='BOOKED' AND b.bill_date >= current_date - 90
				GROUP BY p.name ORDER BY 2 DESC LIMIT 5
				""", companyId);
	}

	private int count(String sql, Object... args) {
		Integer n = jdbc.queryForObject(sql, Integer.class, args);
		return n == null ? 0 : n;
	}
}
