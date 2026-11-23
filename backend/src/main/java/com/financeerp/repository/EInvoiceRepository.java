package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class EInvoiceRepository {
	private final JdbcTemplate jdbc;

	public EInvoiceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findInvoice(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT id,invoice_number AS num,invoice_date AS dt,place_of_supply AS pos,supply_type AS supply,total_amount AS total,tcs_amount AS tcs,taxable_amount AS taxable,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,customer_id AS customer,status,irn FROM sales_invoices WHERE company_id=? AND id=?",
				companyId, invoiceId);
	}

	public Map<String, Object> findSeller(long companyId) {
		return jdbc.queryForMap(
				"SELECT gstin,COALESCE(legal_name,name) AS name,address_line AS addr,city,pincode,state_code AS state FROM companies WHERE id=?",
				companyId);
	}

	public Map<String, Object> findBuyer(long companyId, Object customerId) {
		return jdbc.queryForMap(
				"SELECT gstin,name,address_line AS addr,city,pincode,state_code AS state FROM parties WHERE company_id=? AND id=?",
				companyId, customerId);
	}

	public List<Map<String, Object>> findInvoiceLines(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT description,hsn_sac AS hsn,quantity AS qty,unit_price AS price,gst_rate AS rate,taxable_amount AS taxable,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? ORDER BY id",
				companyId, invoiceId);
	}

	public int recordIrn(long companyId, long invoiceId, String irn) {
		return jdbc.update(
				"UPDATE sales_invoices SET irn=?,irn_recorded_at=now() WHERE company_id=? AND id=? AND status='ISSUED' AND irn IS NULL",
				irn, companyId, invoiceId);
	}
}
