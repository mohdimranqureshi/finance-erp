package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class PurchaseBillRepository {
	private static final String SELECT = """
			SELECT b.id,b.vendor_invoice_number AS "vendorInvoiceNumber",b.bill_date AS "billDate",b.due_date AS "dueDate",
			 b.vendor_id AS "vendorId",p.name AS "vendorName",b.place_of_supply AS "placeOfSupply",
			 b.supply_type AS "supplyType",b.taxable_amount AS "taxableAmount",b.cgst_amount AS "cgstAmount",
			 b.sgst_amount AS "sgstAmount",b.igst_amount AS "igstAmount",b.total_amount AS "totalAmount",
			 b.itc_eligible AS "itcEligible",b.tds_section AS "tdsSection",b.tds_rate AS "tdsRate",
			 b.tds_amount AS "tdsAmount",b.reverse_charge AS "reverseCharge",b.status,b.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM purchase_bills b
			JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
			LEFT JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
			""";
	private final JdbcTemplate jdbc;

	public PurchaseBillRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Expense account with its summed taxable and GST amounts for one bill. */
	public record AccountTotal(long accountId, BigDecimal taxable, BigDecimal tax) {
	}

	public int countByVendorInvoiceNumber(long companyId, long vendorId, String vendorInvoiceNumber) {
		Integer duplicate = jdbc.queryForObject(
				"SELECT count(*) FROM purchase_bills WHERE company_id=? AND vendor_id=? AND lower(vendor_invoice_number)=lower(?)",
				Integer.class, companyId, vendorId, vendorInvoiceNumber);
		return duplicate == null ? 0 : duplicate;
	}

	public Long insertPurchaseBill(long companyId, Long vendorId, String vendorInvoiceNumber, LocalDate billDate,
			LocalDate dueDate, String placeOfSupply, String supplyType, BigDecimal taxable, BigDecimal cgst,
			BigDecimal sgst, BigDecimal igst, BigDecimal total, boolean itc, String tdsSection, BigDecimal tdsRate,
			BigDecimal tds, long actor, boolean reverseCharge) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_bills(company_id,vendor_id,vendor_invoice_number,bill_date,due_date,place_of_supply,supply_type,taxable_amount,cgst_amount,sgst_amount,igst_amount,total_amount,itc_eligible,tds_section,tds_rate,tds_amount,created_by_user_id,reverse_charge) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, vendorId, vendorInvoiceNumber, billDate, dueDate, placeOfSupply, supplyType,
				taxable, cgst, sgst, igst, total, itc, tdsSection, tdsRate, tds, actor, reverseCharge);
	}

	public void insertPurchaseBillLine(long companyId, Long billId, String description, String hsnSac,
			BigDecimal quantity, BigDecimal unitPrice, BigDecimal gstRate, BigDecimal taxable, BigDecimal cgst,
			BigDecimal sgst, BigDecimal igst, Long expenseAccountId) {
		jdbc.update(
				"INSERT INTO purchase_bill_lines(company_id,bill_id,description,hsn_sac,quantity,unit_price,gst_rate,taxable_amount,cgst_amount,sgst_amount,igst_amount,expense_account_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
				companyId, billId, description, hsnSac, quantity, unitPrice, gstRate, taxable, cgst, sgst, igst,
				expenseAccountId);
	}

	public List<Map<String, Object>> findBills(long companyId) {
		return jdbc.queryForList(SELECT + " WHERE b.company_id=? ORDER BY b.bill_date DESC,b.id DESC", companyId);
	}

	public List<Map<String, Object>> findBill(long companyId, long billId) {
		return jdbc.queryForList(SELECT + " WHERE b.company_id=? AND b.id=?", companyId, billId);
	}

	public List<Map<String, Object>> findBillLines(long companyId, long billId) {
		return jdbc.queryForList(
				"SELECT id,description,hsn_sac AS \"hsnSac\",quantity,unit_price AS \"unitPrice\",gst_rate AS \"gstRate\",taxable_amount AS \"taxableAmount\",cgst_amount AS \"cgstAmount\",sgst_amount AS \"sgstAmount\",igst_amount AS \"igstAmount\",expense_account_id AS \"expenseAccountId\" FROM purchase_bill_lines WHERE company_id=? AND bill_id=? ORDER BY id",
				companyId, billId);
	}

	public List<Map<String, Object>> lockBillForBooking(long companyId, long billId) {
		return jdbc.queryForList(
				"SELECT status,bill_date AS \"billDate\",vendor_invoice_number AS ref,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,total_amount AS total,tds_amount AS tds,itc_eligible AS itc,reverse_charge AS rcm FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE",
				companyId, billId);
	}

	public List<AccountTotal> findAccountTotals(long companyId, long billId) {
		return jdbc.query(
				"SELECT expense_account_id,SUM(taxable_amount),SUM(cgst_amount+sgst_amount+igst_amount) FROM purchase_bill_lines WHERE company_id=? AND bill_id=? GROUP BY expense_account_id ORDER BY expense_account_id",
				(rs, i) -> new AccountTotal(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), companyId,
				billId);
	}

	public void markBooked(long companyId, long billId, long journalId) {
		jdbc.update(
				"UPDATE purchase_bills SET status='BOOKED',journal_entry_id=?,booked_at=now() WHERE company_id=? AND id=?",
				journalId, companyId, billId);
	}
}
