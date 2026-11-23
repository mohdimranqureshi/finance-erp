package com.financeerp.repository;

import com.financeerp.domain.Gstr2bMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class Gstr2bRepository {
	private static final String IMPORT_SELECT = """
			SELECT id,return_period AS "returnPeriod",recipient_gstin AS "recipientGstin",line_count AS "lineCount",
			 skipped_count AS "skippedDocuments",imported_at AS "importedAt",reconciled_at AS "reconciledAt"
			FROM gstr2b_imports""";
	private static final String ITEM_SELECT = """
			SELECT r.id,r.status,r.reasons,r.resolution,r.resolution_note AS "resolutionNote",r.resolved_at AS "resolvedAt",
			 r.line_id AS "lineId",l.doc_type AS "docType",l.supplier_gstin AS "supplierGstin",l.supplier_name AS "supplierName",
			 l.invoice_number AS "portalNumber",l.invoice_date AS "portalDate",l.original_invoice_number AS "portalOriginalNumber",
			 l.taxable_amount AS "portalTaxable",l.igst_amount+l.cgst_amount+l.sgst_amount AS "portalTax",
			 r.bill_id AS "billId",b.vendor_invoice_number AS "billInvoiceNumber",b.bill_date AS "billDate",
			 b.taxable_amount AS "billTaxable",b.igst_amount+b.cgst_amount+b.sgst_amount AS "billTax",b.status AS "billStatus",
			 r.note_id AS "noteId",n.note_number AS "noteNumber",n.note_date AS "noteDate",
			 n.taxable_amount AS "noteTaxable",n.igst_amount+n.cgst_amount+n.sgst_amount AS "noteTax",
			 p.id AS "vendorId",p.name AS "vendorName"
			FROM gstr2b_recon_items r
			LEFT JOIN gstr2b_lines l ON l.company_id=r.company_id AND l.id=r.line_id
			LEFT JOIN purchase_bills b ON b.company_id=r.company_id AND b.id=r.bill_id
			LEFT JOIN adjustment_notes n ON n.company_id=r.company_id AND n.id=r.note_id
			LEFT JOIN purchase_bills nb ON nb.company_id=n.company_id AND nb.id=n.purchase_bill_id
			LEFT JOIN parties p ON p.company_id=r.company_id AND p.id=COALESCE(b.vendor_id,nb.vendor_id)
			""";
	private static final String ITC_SQL = """
			SELECT
			 COALESCE(SUM(CASE WHEN r.status='MATCHED' AND l.doc_type<>'CREDIT_NOTE'
			   THEN l.igst_amount+l.cgst_amount+l.sgst_amount ELSE 0 END),0) AS "itcMatched",
			 COALESCE(SUM(CASE
			   WHEN r.bill_id IS NULL THEN 0
			   WHEN r.status IN ('MISSING_IN_2B','ITC_NOT_AVAILABLE') THEN b.igst_amount+b.cgst_amount+b.sgst_amount
			   WHEN r.status='MISMATCH' THEN GREATEST(0,(b.igst_amount+b.cgst_amount+b.sgst_amount)-(l.igst_amount+l.cgst_amount+l.sgst_amount))
			   ELSE 0 END),0) AS "itcAtRisk",
			 COALESCE(SUM(CASE WHEN r.status='MISSING_IN_BOOKS' AND l.doc_type<>'CREDIT_NOTE' AND l.itc_available
			   THEN l.igst_amount+l.cgst_amount+l.sgst_amount ELSE 0 END),0) AS "itcNotRecordedInBooks",
			 COALESCE(SUM(CASE WHEN r.status='MISSING_IN_BOOKS' AND l.doc_type='CREDIT_NOTE'
			   THEN l.igst_amount+l.cgst_amount+l.sgst_amount ELSE 0 END),0) AS "itcToReverse",
			 COUNT(*) FILTER (WHERE r.status<>'MATCHED' AND r.resolution IS NULL) AS "openExceptions"
			FROM gstr2b_recon_items r
			LEFT JOIN gstr2b_lines l ON l.company_id=r.company_id AND l.id=r.line_id
			LEFT JOIN purchase_bills b ON b.company_id=r.company_id AND b.id=r.bill_id
			WHERE r.company_id=? AND r.import_id=?
			""";
	private static final String FOLLOW_UP_FROM = """
			FROM gstr2b_recon_items r
			LEFT JOIN purchase_bills b ON b.company_id=r.company_id AND b.id=r.bill_id
			LEFT JOIN adjustment_notes n ON n.company_id=r.company_id AND n.id=r.note_id
			LEFT JOIN purchase_bills nb ON nb.company_id=n.company_id AND nb.id=n.purchase_bill_id
			JOIN parties p ON p.company_id=r.company_id AND p.id=COALESCE(b.vendor_id,nb.vendor_id)
			WHERE r.company_id=? AND r.import_id=? AND r.status='MISSING_IN_2B'
			 AND (r.resolution IS NULL OR r.resolution='FOLLOW_UP_VENDOR')
			""";

	private final JdbcTemplate jdbc;

	public Gstr2bRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A GSTR-2B portal document together with its doc type, used to split invoices from credit notes. */
	public record PortalLine(String docType, Gstr2bMatcher.SupplierLine line) {
	}

	/** A book document (bill or purchase debit note) and whether it claims ITC. */
	public record BookDoc(Gstr2bMatcher.Doc doc, boolean claimsItc) {
	}

	public record KeptResolution(Long lineId, Long billId, Long noteId, String status, String resolution, String note,
			Long resolvedByUserId, OffsetDateTime resolvedAt) {
	}

	public Integer countImportsForPeriod(long companyId, String period) {
		return jdbc.queryForObject("SELECT count(*) FROM gstr2b_imports WHERE company_id=? AND return_period=?",
				Integer.class, companyId, period);
	}

	public Long insertImport(long companyId, String period, String recipient, String sha256, int lineCount, int skipped,
			long actor) {
		return jdbc.queryForObject(
				"INSERT INTO gstr2b_imports(company_id,return_period,recipient_gstin,source_sha256,line_count,skipped_count,imported_by_user_id) VALUES (?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, period, recipient, sha256, lineCount, skipped, actor);
	}

	public void insertLines(List<Object[]> rows) {
		jdbc.batchUpdate(
				"INSERT INTO gstr2b_lines(company_id,import_id,supplier_gstin,supplier_name,doc_type,invoice_number,invoice_date,original_invoice_number,original_invoice_date,invoice_value,taxable_amount,igst_amount,cgst_amount,sgst_amount,cess_amount,itc_available,itc_unavailable_reason,reverse_charge,rate_breakup) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
				rows);
	}

	public List<Map<String, Object>> findImports(long companyId) {
		return jdbc.queryForList(IMPORT_SELECT
				+ " WHERE company_id=? ORDER BY substring(return_period,3,4) DESC,substring(return_period,1,2) DESC",
				companyId);
	}

	public List<Map<String, Object>> findImport(long companyId, long importId) {
		return jdbc.queryForList(IMPORT_SELECT + " WHERE company_id=? AND id=?", companyId, importId);
	}

	public Map<String, Long> countItemsByStatus(long companyId, long importId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbc.query("SELECT status,count(*) FROM gstr2b_recon_items WHERE company_id=? AND import_id=? GROUP BY status",
				(RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)), companyId, importId);
		return counts;
	}

	public Map<String, Object> findItcSummary(long companyId, long importId) {
		return jdbc.queryForMap(ITC_SQL, companyId, importId);
	}

	public List<Map<String, Object>> findItems(long companyId, long importId) {
		return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.import_id=? ORDER BY r.status,r.id",
				companyId, importId);
	}

	public List<Map<String, Object>> findItemsByStatus(long companyId, long importId, String status) {
		return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.import_id=? AND r.status=? ORDER BY r.id",
				companyId, importId, status);
	}

	public List<Map<String, Object>> findItem(long companyId, long itemId) {
		return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.id=?", companyId, itemId);
	}

	public List<Map<String, Object>> findVendorFollowUps(long companyId, long importId) {
		return jdbc.queryForList("""
				SELECT p.id AS "vendorId",p.name AS "vendorName",p.gstin,p.email,count(*) AS "documents",
				 COALESCE(sum(b.igst_amount+b.cgst_amount+b.sgst_amount),0) AS "itcAtRisk",
				 string_agg(COALESCE(b.vendor_invoice_number,n.note_number), ', '
				  ORDER BY COALESCE(b.bill_date,n.note_date),r.id) AS "documentNumbers"
				""" + FOLLOW_UP_FROM + """
				GROUP BY p.id,p.name,p.gstin,p.email
				ORDER BY "itcAtRisk" DESC,p.name
				""", companyId, importId);
	}

	public List<Map<String, Object>> findFollowUpDocuments(long companyId, long importId, long vendorId) {
		return jdbc.queryForList("""
				SELECT CASE WHEN r.bill_id IS NOT NULL THEN 'Invoice' ELSE 'Credit note' END AS "kind",
				 COALESCE(b.vendor_invoice_number,n.note_number) AS "number",
				 COALESCE(b.bill_date,n.note_date) AS "documentDate",
				 COALESCE(b.taxable_amount,n.taxable_amount) AS "taxable",
				 COALESCE(b.igst_amount+b.cgst_amount+b.sgst_amount,n.igst_amount+n.cgst_amount+n.sgst_amount) AS "tax",
				 COALESCE(b.total_amount,n.total_amount) AS "total"
				""" + FOLLOW_UP_FROM + " AND p.id=? ORDER BY 3,r.id", companyId, importId, vendorId);
	}

	public int resolveItem(long companyId, long itemId, String resolution, String note, long actor) {
		return jdbc.update(
				"UPDATE gstr2b_recon_items SET resolution=?,resolution_note=?,resolved_by_user_id=?,resolved_at=now() WHERE company_id=? AND id=? AND status<>'MATCHED'",
				resolution, note, actor, companyId, itemId);
	}

	public int deleteImport(long companyId, long importId) {
		return jdbc.update("DELETE FROM gstr2b_imports WHERE company_id=? AND id=?", companyId, importId);
	}

	public List<String> lockImportPeriod(long companyId, long importId) {
		return jdbc.queryForList("SELECT return_period FROM gstr2b_imports WHERE company_id=? AND id=? FOR UPDATE",
				String.class, companyId, importId);
	}

	public List<PortalLine> findPortalLines(long companyId, long importId) {
		List<PortalLine> out = new ArrayList<>();
		jdbc.query(
				"SELECT id,supplier_gstin,invoice_number,invoice_date,taxable_amount,igst_amount,cgst_amount,sgst_amount,itc_available,reverse_charge,doc_type,original_invoice_number FROM gstr2b_lines WHERE company_id=? AND import_id=? ORDER BY id",
				(RowCallbackHandler) rs -> out.add(new PortalLine(rs.getString(11),
						new Gstr2bMatcher.SupplierLine(doc(rs), rs.getBoolean(9), rs.getBoolean(10), rs.getString(12)))),
				companyId, importId);
		return out;
	}

	public List<BookDoc> findBillsForMatching(long companyId, LocalDate windowStart, LocalDate periodEnd, long importId) {
		return jdbc.query("""
				SELECT b.id,p.gstin,b.vendor_invoice_number,b.bill_date,b.taxable_amount,b.igst_amount,b.cgst_amount,
				 b.sgst_amount,b.itc_eligible,b.reverse_charge
				FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE b.company_id=? AND p.gstin IS NOT NULL AND b.bill_date BETWEEN ? AND ?
				 AND NOT EXISTS (SELECT 1 FROM gstr2b_recon_items r WHERE r.company_id=b.company_id AND r.bill_id=b.id
				  AND r.import_id<>? AND r.status<>'MISSING_IN_2B')
				ORDER BY b.bill_date,b.id""", (rs, i) -> {
			Gstr2bMatcher.Doc d = doc(rs);
			return new BookDoc(new Gstr2bMatcher.Doc(d.id(), d.gstin(), d.invoiceNumber(), d.date(), d.taxable(), d.igst(),
					d.cgst(), d.sgst(), rs.getBoolean(10)), rs.getBoolean(9));
		}, companyId, windowStart, periodEnd, importId);
	}

	public List<BookDoc> findPurchaseDebitNotesForMatching(long companyId, LocalDate windowStart, LocalDate periodEnd,
			long importId) {
		return jdbc.query("""
				SELECT n.id,p.gstin,n.note_number,n.note_date,n.taxable_amount,n.igst_amount,n.cgst_amount,
				 n.sgst_amount,b.itc_eligible
				FROM adjustment_notes n
				JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id
				JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND p.gstin IS NOT NULL
				 AND n.note_date BETWEEN ? AND ?
				 AND NOT EXISTS (SELECT 1 FROM gstr2b_recon_items r WHERE r.company_id=n.company_id AND r.note_id=n.id
				  AND r.import_id<>? AND r.status<>'MISSING_IN_2B')
				ORDER BY n.note_date,n.id""", (rs, i) -> new BookDoc(doc(rs), rs.getBoolean(9)), companyId, windowStart,
				periodEnd, importId);
	}

	public List<KeptResolution> findKeptResolutions(long companyId, long importId) {
		List<KeptResolution> out = new ArrayList<>();
		jdbc.query(
				"SELECT line_id,bill_id,note_id,status,resolution,resolution_note,resolved_by_user_id,resolved_at FROM gstr2b_recon_items WHERE company_id=? AND import_id=? AND resolution IS NOT NULL",
				(RowCallbackHandler) rs -> out.add(new KeptResolution(rs.getObject(1, Long.class),
						rs.getObject(2, Long.class), rs.getObject(3, Long.class), rs.getString(4), rs.getString(5),
						rs.getString(6), rs.getObject(7, Long.class), rs.getObject(8, OffsetDateTime.class))),
				companyId, importId);
		return out;
	}

	public void deleteReconItems(long companyId, long importId) {
		jdbc.update("DELETE FROM gstr2b_recon_items WHERE company_id=? AND import_id=?", companyId, importId);
	}

	public void insertReconItems(List<Object[]> rows) {
		jdbc.batchUpdate(
				"INSERT INTO gstr2b_recon_items(company_id,import_id,line_id,bill_id,note_id,status,reasons,resolution,resolution_note,resolved_by_user_id,resolved_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
				rows);
	}

	public void markReconciled(long companyId, long importId) {
		jdbc.update("UPDATE gstr2b_imports SET reconciled_at=now() WHERE company_id=? AND id=?", companyId, importId);
	}

	private static Gstr2bMatcher.Doc doc(ResultSet rs) throws SQLException {
		return new Gstr2bMatcher.Doc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
				rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8));
	}
}
