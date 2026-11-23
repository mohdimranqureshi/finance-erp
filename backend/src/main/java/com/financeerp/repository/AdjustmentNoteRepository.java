package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class AdjustmentNoteRepository {
	private final JdbcTemplate jdbc;

	public AdjustmentNoteRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> lockSalesInvoice(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT status,invoice_number AS number,invoice_date AS date,supply_type AS supply,total_amount AS total FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE",
				companyId, invoiceId);
	}

	public List<Map<String, Object>> lockPurchaseBill(long companyId, long billId) {
		return jdbc.queryForList(
				"SELECT b.status,b.bill_date AS date,b.supply_type AS supply,b.total_amount AS total,b.itc_eligible AS itc,b.reverse_charge AS rcm,p.gstin AS gstin FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id WHERE b.company_id=? AND b.id=? FOR UPDATE OF b",
				companyId, billId);
	}

	public List<Map<String, Object>> findNotes(long companyId) {
		return jdbc.queryForList(
				"SELECT n.id,n.note_type AS \"noteType\",n.note_number AS \"noteNumber\",n.note_date AS \"noteDate\",n.reason,n.sales_invoice_id AS \"salesInvoiceId\",n.purchase_bill_id AS \"purchaseBillId\",n.taxable_amount AS \"taxableAmount\",n.cgst_amount AS \"cgstAmount\",n.sgst_amount AS \"sgstAmount\",n.igst_amount AS \"igstAmount\",n.total_amount AS \"totalAmount\",n.journal_entry_id AS \"journalEntryId\",j.status AS \"journalStatus\" FROM adjustment_notes n JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id WHERE n.company_id=? ORDER BY n.note_date DESC,n.id DESC",
				companyId);
	}

	/** Sum of the notes already raised against one sales invoice (salesInvoice) or purchase bill. */
	public BigDecimal sumNoteTotals(long companyId, boolean salesInvoice, long sourceId) {
		String column = salesInvoice ? "sales_invoice_id" : "purchase_bill_id";
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(total_amount),0) FROM adjustment_notes WHERE company_id=? AND " + column + "=?",
				BigDecimal.class, companyId, sourceId);
	}

	public Long insertNote(long companyId, boolean salesInvoice, String type, long sourceId, String number,
			LocalDate noteDate, String reason, String supply, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
			BigDecimal igst, BigDecimal total, long journalId, long actor) {
		return jdbc.queryForObject("INSERT INTO adjustment_notes(company_id,note_type,"
				+ (salesInvoice ? "sales_invoice_id" : "purchase_bill_id")
				+ ",note_number,note_date,reason,supply_type,taxable_amount,cgst_amount,sgst_amount,igst_amount,total_amount,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, type, sourceId, number, noteDate, reason, supply, taxable, cgst, sgst, igst,
				total, journalId, actor);
	}

	public void insertNoteLine(long companyId, Long noteId, String description, String hsnSac, BigDecimal quantity,
			BigDecimal unitPrice, BigDecimal gstRate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
			BigDecimal igst, Long accountId) {
		jdbc.update(
				"INSERT INTO adjustment_note_lines(company_id,note_id,description,hsn_sac,quantity,unit_price,gst_rate,taxable_amount,cgst_amount,sgst_amount,igst_amount,account_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
				companyId, noteId, description, hsnSac, quantity, unitPrice, gstRate, taxable, cgst, sgst, igst,
				accountId);
	}
}
