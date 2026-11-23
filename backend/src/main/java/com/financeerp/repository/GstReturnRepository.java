package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class GstReturnRepository {
	private final JdbcTemplate jdbc;

	public GstReturnRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findOutwardInvoiceLines(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("""
				SELECT i.id,i.invoice_number AS num,i.invoice_date AS dt,i.place_of_supply AS pos,i.total_amount AS val,
				       i.total_amount-i.tcs_amount AS gstval,
				       i.supply_type AS supply,p.gstin AS ctin,l.gst_rate AS rt,l.hsn_sac AS hsn,l.quantity AS qty,
				       l.taxable_amount AS txval,l.cgst_amount AS camt,l.sgst_amount AS samt,l.igst_amount AS iamt
				FROM sales_invoices i
				JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND (r.id IS NULL OR r.status<>'POSTED')
				ORDER BY i.invoice_number,l.id
				""", companyId, from, to);
	}

	public List<Map<String, Object>> findSalesCreditNotes(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("""
				SELECT n.note_number AS nt_num,n.note_date AS nt_dt,i.invoice_number AS inum,p.gstin AS ctin,
				       n.total_amount AS val,n.taxable_amount AS txval,n.cgst_amount AS camt,n.sgst_amount AS samt,
				       n.igst_amount AS iamt,i.place_of_supply AS pos
				FROM adjustment_notes n
				JOIN sales_invoices i ON i.company_id=n.company_id AND i.id=n.sales_invoice_id
				JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				ORDER BY n.note_number
				""", companyId, from, to);
	}
}
