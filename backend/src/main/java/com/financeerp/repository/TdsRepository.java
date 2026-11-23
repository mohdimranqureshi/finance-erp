package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class TdsRepository {
	/** Booked bills with TDS whose journal is posted and not reversed. */
	private static final String DEDUCTIONS = """
			SELECT b.id AS "billId",b.vendor_invoice_number AS "billNumber",b.bill_date AS "deductionDate",
			 p.id AS "vendorId",p.name AS "vendorName",p.pan,b.tds_section AS section,b.tds_rate AS rate,
			 b.taxable_amount AS "amountPaidOrCredited",b.tds_amount AS "tdsDeducted",
			 COALESCE((SELECT SUM(a.amount) FROM tds_challan_allocations a WHERE a.company_id=b.company_id AND a.bill_id=b.id),0) AS allocated
			FROM purchase_bills b
			JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
			JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id AND j.status='POSTED'
			LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
			WHERE b.company_id=? AND b.tds_amount>0 AND (r.id IS NULL OR r.status<>'POSTED')
			""";
	private static final String CHALLAN_SELECT = """
			SELECT c.id,c.bsr_code AS "bsrCode",c.challan_serial AS "challanSerial",c.deposit_date AS "depositDate",
			 c.tax_amount AS "taxAmount",c.interest_amount AS "interestAmount",c.fee_amount AS "feeAmount",
			 c.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",
			 COALESCE((SELECT SUM(a.amount) FROM tds_challan_allocations a WHERE a.company_id=c.company_id AND a.challan_id=c.id),0) AS allocated
			FROM tds_challans c JOIN journal_entries j ON j.company_id=c.company_id AND j.id=c.journal_entry_id
			""";

	private final JdbcTemplate jdbc;

	public TdsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findDeductions(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList(DEDUCTIONS + " AND b.bill_date BETWEEN ? AND ? ORDER BY b.bill_date,b.id", companyId,
				from, to);
	}

	public List<Map<String, Object>> findDeductionByBill(long companyId, Long billId) {
		return jdbc.queryForList(DEDUCTIONS + " AND b.id=?", companyId, billId);
	}

	public Long insertChallan(long companyId, String bsrCode, String challanSerial, LocalDate depositDate,
			BigDecimal taxAmount, BigDecimal interest, BigDecimal fee, Long bankAccountId, long journalId, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO tds_challans(company_id,bsr_code,challan_serial,deposit_date,tax_amount,interest_amount,fee_amount,bank_account_id,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, bsrCode, challanSerial, depositDate, taxAmount, interest, fee, bankAccountId,
				journalId, actor);
	}

	public List<Map<String, Object>> findChallans(long companyId) {
		return jdbc.queryForList(CHALLAN_SELECT + " WHERE c.company_id=? ORDER BY c.deposit_date DESC,c.id DESC", companyId);
	}

	public List<Map<String, Object>> findChallan(long companyId, long challanId) {
		return jdbc.queryForList(CHALLAN_SELECT + " WHERE c.company_id=? AND c.id=?", companyId, challanId);
	}

	public List<Map<String, Object>> findChallanAllocations(long companyId, long challanId) {
		return jdbc.queryForList("""
				SELECT a.bill_id AS "billId",b.vendor_invoice_number AS "billNumber",p.name AS "vendorName",a.amount
				FROM tds_challan_allocations a
				JOIN purchase_bills b ON b.company_id=a.company_id AND b.id=a.bill_id
				JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE a.company_id=? AND a.challan_id=? ORDER BY a.id""", companyId, challanId);
	}

	public Map<String, Object> findDeductor(long companyId) {
		return jdbc.queryForMap("SELECT name,tan,pan FROM companies WHERE id=?", companyId);
	}

	public List<Map<String, Object>> findAllocationsWithDeposits(long companyId) {
		return jdbc.queryForList("""
				SELECT a.bill_id AS "billId",a.amount,c.bsr_code AS "bsrCode",c.challan_serial AS "challanSerial",
				 c.deposit_date AS "depositDate"
				FROM tds_challan_allocations a JOIN tds_challans c ON c.company_id=a.company_id AND c.id=a.challan_id
				WHERE a.company_id=? ORDER BY c.deposit_date,a.id""", companyId);
	}

	public List<Map<String, Object>> findChallansAllocatedToBillsBetween(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList(CHALLAN_SELECT
				+ " WHERE c.company_id=? AND c.id IN (SELECT a.challan_id FROM tds_challan_allocations a JOIN purchase_bills b ON b.company_id=a.company_id AND b.id=a.bill_id WHERE a.company_id=? AND b.bill_date BETWEEN ? AND ?) ORDER BY c.deposit_date",
				companyId, companyId, from, to);
	}

	public List<Map<String, Object>> lockChallan(long companyId, long challanId) {
		return jdbc.queryForList(
				"SELECT tax_amount AS tax,deposit_date AS date FROM tds_challans WHERE company_id=? AND id=? FOR UPDATE",
				companyId, challanId);
	}

	public BigDecimal sumAllocations(long companyId, long challanId) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM tds_challan_allocations WHERE company_id=? AND challan_id=?",
				BigDecimal.class, companyId, challanId);
	}

	public void lockPurchaseBill(long companyId, Long billId) {
		jdbc.queryForList("SELECT id FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE", companyId, billId);
	}

	public void upsertAllocation(long companyId, long challanId, Long billId, BigDecimal amount, long actor) {
		jdbc.update(
				"INSERT INTO tds_challan_allocations(company_id,challan_id,bill_id,amount,created_by_user_id) VALUES (?,?,?,?,?) ON CONFLICT (company_id,challan_id,bill_id) DO UPDATE SET amount=tds_challan_allocations.amount+EXCLUDED.amount",
				companyId, challanId, billId, amount, actor);
	}
}
