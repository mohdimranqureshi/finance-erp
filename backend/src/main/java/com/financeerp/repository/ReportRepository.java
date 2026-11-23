package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ReportRepository {
	private static final String BALANCES = """
			SELECT a.id AS "accountId", a.code, a.name, COALESCE(SUM(l.credit-l.debit),0) AS "netCredit"
			FROM accounts a
			JOIN journal_lines l ON l.company_id=a.company_id AND l.account_id=a.id
			JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
			WHERE a.company_id=? AND je.status='POSTED' AND a.account_type=? AND je.journal_date BETWEEN ? AND ?
			  AND (? OR NOT je.is_closing)
			GROUP BY a.id,a.code,a.name ORDER BY a.code
			""";

	private final JdbcTemplate jdbc;

	public ReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findTrialBalance(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("""
				SELECT a.id AS "accountId", a.code, a.name, a.account_type AS "accountType",
				       COALESCE(SUM(l.debit),0) AS "debitTotal",
				       COALESCE(SUM(l.credit),0) AS "creditTotal",
				       COALESCE(SUM(l.debit-l.credit),0) AS "netDebit",
				       MAX(l.journal_date) AS "lastEntryDate"
				FROM accounts a
				LEFT JOIN (
				    SELECT jl.company_id, jl.account_id, jl.debit, jl.credit, je.journal_date
				    FROM journal_lines jl
				    JOIN journal_entries je ON je.company_id=jl.company_id AND je.id=jl.journal_entry_id
				    WHERE je.status='POSTED' AND je.journal_date BETWEEN ? AND ?
				) l ON l.company_id=a.company_id AND l.account_id=a.id
				WHERE a.company_id=?
				GROUP BY a.id,a.code,a.name,a.account_type ORDER BY a.code
				""", from, to, companyId);
	}

	/** Raw rows with the credit-positive "netCredit" of each account of the given type. */
	public List<Map<String, Object>> findBalanceRows(long companyId, String type, LocalDate from, LocalDate to,
			boolean includeClosing) {
		return jdbc.queryForList(BALANCES, companyId, type, from, to, includeClosing);
	}

	public Optional<Map<String, Object>> findAccount(long companyId, long accountId) {
		return jdbc.queryForList(
				"SELECT id,code,name,account_type AS \"accountType\" FROM accounts WHERE company_id=? AND id=?",
				companyId, accountId).stream().findFirst();
	}

	public BigDecimal findOpeningDebitMinusCredit(long companyId, long accountId, LocalDate from) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l
				JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				WHERE l.company_id=? AND l.account_id=? AND je.status='POSTED' AND je.journal_date < ?
				""", BigDecimal.class, companyId, accountId, from);
	}

	public List<Map<String, Object>> findLedgerRows(long companyId, long accountId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("""
				SELECT je.id AS "journalId", je.journal_date AS "date", je.description,
				       l.description AS "lineDescription", l.debit, l.credit
				FROM journal_lines l
				JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				WHERE l.company_id=? AND l.account_id=? AND je.status='POSTED' AND je.journal_date BETWEEN ? AND ?
				ORDER BY je.journal_date, je.id, l.id
				""", companyId, accountId, from, to);
	}

	public Map<String, Object> findOutwardSupplies(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(i.taxable_amount),0) AS "taxableValue", COALESCE(SUM(i.cgst_amount),0) AS cgst,
				       COALESCE(SUM(i.sgst_amount),0) AS sgst, COALESCE(SUM(i.igst_amount),0) AS igst
				FROM sales_invoices i
				JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
	}

	public Map<String, Object> findEligibleItc(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(b.taxable_amount),0) AS "taxableValue", COALESCE(SUM(b.cgst_amount),0) AS cgst,
				       COALESCE(SUM(b.sgst_amount),0) AS sgst, COALESCE(SUM(b.igst_amount),0) AS igst
				FROM purchase_bills b
				JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE b.company_id=? AND b.bill_date BETWEEN ? AND ? AND b.itc_eligible
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
	}

	public Map<String, Object> findSalesCreditNotes(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(n.taxable_amount),0) AS "taxableValue", COALESCE(SUM(n.cgst_amount),0) AS cgst,
				       COALESCE(SUM(n.sgst_amount),0) AS sgst, COALESCE(SUM(n.igst_amount),0) AS igst
				FROM adjustment_notes n
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
	}

	public Map<String, Object> findPurchaseDebitNotes(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(n.taxable_amount),0) AS "taxableValue", COALESCE(SUM(n.cgst_amount),0) AS cgst,
				       COALESCE(SUM(n.sgst_amount),0) AS sgst, COALESCE(SUM(n.igst_amount),0) AS igst
				FROM adjustment_notes n
				JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id AND b.itc_eligible
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
	}

	public Map<String, Object> findReverseCharge(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(b.taxable_amount),0) AS "taxableValue", COALESCE(SUM(b.cgst_amount),0) AS cgst,
				       COALESCE(SUM(b.sgst_amount),0) AS sgst, COALESCE(SUM(b.igst_amount),0) AS igst
				FROM purchase_bills b
				JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE b.company_id=? AND b.bill_date BETWEEN ? AND ? AND b.reverse_charge
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
	}
}
