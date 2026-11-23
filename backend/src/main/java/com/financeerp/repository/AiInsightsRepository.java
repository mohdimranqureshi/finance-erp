package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiInsightsRepository {
	private final JdbcTemplate jdbc;

	public AiInsightsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int countStaleJournals(long companyId) {
		return count("SELECT count(*) FROM journal_entries WHERE company_id=? AND status IN ('DRAFT','SUBMITTED','APPROVED') AND created_at < now() - interval '7 days'", companyId);
	}

	public int countUnpostedInvoices(long companyId) {
		return count("SELECT count(*) FROM sales_invoices i JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id WHERE i.company_id=? AND j.status<>'POSTED' AND i.issued_at < now() - interval '3 days'", companyId);
	}

	public int countDuplicateBills(long companyId) {
		return count("SELECT count(*) FROM purchase_bills a JOIN purchase_bills b ON a.company_id=b.company_id AND a.vendor_id=b.vendor_id AND a.id<b.id AND a.total_amount=b.total_amount AND abs(a.bill_date-b.bill_date)<=7 WHERE a.company_id=?", companyId);
	}

	public int countNoGstRegisteredVendorBills(long companyId) {
		return count("SELECT count(*) FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id WHERE b.company_id=? AND p.gstin IS NOT NULL AND b.cgst_amount+b.sgst_amount+b.igst_amount=0", companyId);
	}

	public int countUnmatchedBankLines(long companyId) {
		return count("SELECT count(*) FROM bank_statement_lines WHERE company_id=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL AND txn_date < current_date - 7", companyId);
	}

	public int countPeriodsOpenAfterEnd(long companyId) {
		return count("SELECT count(*) FROM accounting_periods WHERE company_id=? AND status='OPEN' AND end_date < current_date - 30", companyId);
	}

	public Integer countReceivableAccounts(long companyId) {
		return jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND system_key='AR'", Integer.class, companyId);
	}

	private int count(String sql, long companyId) {
		Integer n = jdbc.queryForObject(sql, Integer.class, companyId);
		return n == null ? 0 : n;
	}
}
