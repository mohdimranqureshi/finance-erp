package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class SalesInvoiceRepository {
	private static final String SELECT = """
			SELECT i.id,i.invoice_number AS "invoiceNumber",i.invoice_date AS "invoiceDate",i.due_date AS "dueDate",
			 i.customer_id AS "customerId",p.name AS "customerName",i.place_of_supply AS "placeOfSupply",
			 i.supply_type AS "supplyType",i.taxable_amount AS "taxableAmount",i.cgst_amount AS "cgstAmount",
			 i.sgst_amount AS "sgstAmount",i.igst_amount AS "igstAmount",i.total_amount AS "totalAmount",
			 i.tcs_section AS "tcsSection",i.tcs_rate AS "tcsRate",i.tcs_amount AS "tcsAmount",
			 i.status,i.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM sales_invoices i
			JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
			LEFT JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id
			""";
	private final JdbcTemplate jdbc;

	public SalesInvoiceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Revenue account with its summed taxable amount for one invoice. */
	public record RevenueTotal(long accountId, BigDecimal taxable) {
	}

	public Long insertSalesInvoice(long companyId, Long customerId, LocalDate invoiceDate, LocalDate dueDate,
			String placeOfSupply, String supplyType, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
			BigDecimal igst, BigDecimal total, long actor, String tcsSection, BigDecimal tcsRate, BigDecimal tcs) {
		return jdbc.queryForObject(
				"INSERT INTO sales_invoices(company_id,customer_id,invoice_date,due_date,place_of_supply,supply_type,taxable_amount,cgst_amount,sgst_amount,igst_amount,total_amount,created_by_user_id,tcs_section,tcs_rate,tcs_amount) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, customerId, invoiceDate, dueDate, placeOfSupply, supplyType, taxable, cgst,
				sgst, igst, total, actor, tcsSection, tcsRate, tcs);
	}

	public void insertSalesInvoiceLine(long companyId, Long invoiceId, String description, String hsnSac,
			BigDecimal quantity, BigDecimal unitPrice, BigDecimal gstRate, BigDecimal taxable, BigDecimal cgst,
			BigDecimal sgst, BigDecimal igst, Long revenueAccountId) {
		jdbc.update(
				"INSERT INTO sales_invoice_lines(company_id,invoice_id,description,hsn_sac,quantity,unit_price,gst_rate,taxable_amount,cgst_amount,sgst_amount,igst_amount,revenue_account_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
				companyId, invoiceId, description, hsnSac, quantity, unitPrice, gstRate, taxable, cgst, sgst, igst,
				revenueAccountId);
	}

	public List<Map<String, Object>> findInvoices(long companyId) {
		return jdbc.queryForList(SELECT + " WHERE i.company_id=? ORDER BY i.invoice_date DESC,i.id DESC", companyId);
	}

	public List<Map<String, Object>> findInvoice(long companyId, long invoiceId) {
		return jdbc.queryForList(SELECT + " WHERE i.company_id=? AND i.id=?", companyId, invoiceId);
	}

	public List<Map<String, Object>> findInvoiceLines(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT id,description,hsn_sac AS \"hsnSac\",quantity,unit_price AS \"unitPrice\",gst_rate AS \"gstRate\",taxable_amount AS \"taxableAmount\",cgst_amount AS \"cgstAmount\",sgst_amount AS \"sgstAmount\",igst_amount AS \"igstAmount\",revenue_account_id AS \"revenueAccountId\" FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? ORDER BY id",
				companyId, invoiceId);
	}

	public List<Map<String, Object>> lockInvoiceForIssue(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT status,invoice_date AS \"invoiceDate\",taxable_amount AS \"taxable\",cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,total_amount AS total,tcs_amount AS tcs,customer_id AS \"customerId\" FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE",
				companyId, invoiceId);
	}

	public List<RevenueTotal> findRevenueTotals(long companyId, long invoiceId) {
		return jdbc.query(
				"SELECT revenue_account_id,SUM(taxable_amount) FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? GROUP BY revenue_account_id ORDER BY revenue_account_id",
				(rs, i) -> new RevenueTotal(rs.getLong(1), rs.getBigDecimal(2)), companyId, invoiceId);
	}

	public void markIssued(long companyId, long invoiceId, String number, long journalId) {
		jdbc.update(
				"UPDATE sales_invoices SET status='ISSUED',invoice_number=?,journal_entry_id=?,issued_at=now() WHERE company_id=? AND id=?",
				number, journalId, companyId, invoiceId);
	}
}
