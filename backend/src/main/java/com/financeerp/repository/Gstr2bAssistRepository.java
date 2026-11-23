package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** GSTR-2B import, vendor and bill-history lookups used by the GSTR-2B assistant. */
@Repository
public class Gstr2bAssistRepository {
	private final JdbcTemplate jdbc;

	public Gstr2bAssistRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<String> findReturnPeriods(long companyId, long importId) {
		return jdbc.queryForList("SELECT return_period FROM gstr2b_imports WHERE company_id=? AND id=?", String.class,
				companyId, importId);
	}

	public List<Map<String, Object>> findVendorContact(long companyId, long vendorId) {
		return jdbc.queryForList("SELECT name,email FROM parties WHERE company_id=? AND id=? AND party_type='VENDOR'", companyId,
				vendorId);
	}

	public Map<String, Object> getCompanyNameAndGstin(long companyId) {
		return jdbc.queryForMap("SELECT name,gstin FROM companies WHERE id=?", companyId);
	}

	public List<Map<String, Object>> findDraftableItem(long companyId, long itemId) {
		return jdbc.queryForList("""
				SELECT l.id AS "lineId",l.supplier_gstin AS gstin,l.supplier_name AS "supplierName",l.doc_type AS "docType",
				 l.invoice_number AS number,l.invoice_date AS "invoiceDate",l.taxable_amount AS taxable,
				 l.igst_amount+l.cgst_amount+l.sgst_amount AS tax,l.itc_available AS "itcAvailable",l.reverse_charge AS "reverseCharge",
				 l.rate_breakup::text AS "rateBreakup",i.return_period AS period
				FROM gstr2b_recon_items r
				JOIN gstr2b_lines l ON l.company_id=r.company_id AND l.id=r.line_id
				JOIN gstr2b_imports i ON i.company_id=r.company_id AND i.id=r.import_id
				WHERE r.company_id=? AND r.id=? AND r.status IN ('MISSING_IN_BOOKS','AMENDMENT_REVIEW')
				""", companyId, itemId);
	}

	public Integer countOpenBillProposalsForLine(long companyId, String lineId) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM ai_proposals WHERE company_id=? AND kind='BILL_EXTRACTION' AND status<>'REJECTED' AND payload->>'gstr2bLineId'=?",
				Integer.class, companyId, lineId);
	}

	public List<Map<String, Object>> findActiveVendorsByGstin(long companyId, String gstin) {
		return jdbc.queryForList(
				"SELECT id FROM parties WHERE company_id=? AND party_type='VENDOR' AND active=true AND gstin=?",
				companyId, gstin);
	}

	public List<Map<String, Object>> findLastBillCoding(long companyId, long vendorId) {
		return jdbc.queryForList(
				"SELECT l.hsn_sac AS hsn,l.expense_account_id AS account FROM purchase_bill_lines l JOIN purchase_bills b ON b.company_id=l.company_id AND b.id=l.bill_id WHERE b.company_id=? AND b.vendor_id=? ORDER BY b.bill_date DESC,b.id DESC,l.id LIMIT 1",
				companyId, vendorId);
	}
}
