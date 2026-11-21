package com.financeerp.india;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/gstr2b")
public class Gstr2bController {
	private static final Set<String> EDITORS = Set.of("OWNER", "ACCOUNTANT");
	private static final Set<String> SUPPORTED_SECTIONS = Set.of("b2b", "b2ba", "cdnr");
	private static final int MAX_LINES = 50_000;
	private static final String CN_MISSING_IN_2B = "Credit note recorded in books but not in this GSTR-2B: ask the supplier to report it in GSTR-1/IFF.";
	private static final String CN_MISSING_IN_BOOKS = "Supplier credit note not recorded: reverse the ITC by recording a debit note against the original bill.";
	private static final DateTimeFormatter PORTAL_DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu")
			.withResolverStyle(ResolverStyle.STRICT);
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
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;
	private final ObjectMapper mapper;

	public Gstr2bController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst,
			ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
		this.mapper = mapper;
	}

	public record ResolveRequest(@NotBlank @Pattern(regexp = "ACCEPTED|FOLLOW_UP_VENDOR|IGNORED") String resolution,
			@Size(max = 500) String note) {
	}

	/** Accepts the GSTR-2B JSON downloaded from the GST portal (with or without the outer "data" wrapper). */
	@PostMapping("/imports")
	@Transactional
	public Map<String, Object> importReturn(@PathVariable long companyId, @RequestBody JsonNode body) {
		long actor = access.requireRole(companyId, EDITORS);
		GstSupport.CompanyProfile company = gst.companyProfile(companyId);
		if (!company.gstRegistered())
			throw new IllegalArgumentException("Set the company GSTIN before importing GSTR-2B.");
		JsonNode data = body.has("data") ? body.get("data") : body;
		String period = data.path("rtnprd").asText("").trim();
		parsePeriod(period);
		String recipient = data.path("gstin").asText("").trim().toUpperCase(Locale.ROOT);
		if (!recipient.equals(company.gstin()))
			throw new IllegalArgumentException("This GSTR-2B is for GSTIN " + recipient + ", not the company GSTIN "
					+ company.gstin() + ".");
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM gstr2b_imports WHERE company_id=? AND return_period=?", Integer.class, companyId,
				period);
		if (existing != null && existing > 0)
			throw new IllegalArgumentException(
					"GSTR-2B for " + period + " is already imported. Delete that import to replace it.");

		JsonNode docdata = data.path("docdata");
		int skipped = 0;
		for (Iterator<Map.Entry<String, JsonNode>> it = docdata.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> section = it.next();
			if (!SUPPORTED_SECTIONS.contains(section.getKey()))
				skipped += countDocs(section.getValue());
		}
		List<Object[]> rows = new ArrayList<>();
		for (String section : List.of("b2b", "b2ba", "cdnr"))
			parseSection(section, docdata.path(section), rows);

		Long importId = jdbc.queryForObject(
				"INSERT INTO gstr2b_imports(company_id,return_period,recipient_gstin,source_sha256,line_count,skipped_count,imported_by_user_id) VALUES (?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, period, recipient, sha256(body.toString()), rows.size(), skipped, actor);
		for (Object[] r : rows) {
			r[0] = companyId;
			r[1] = importId;
		}
		jdbc.batchUpdate(
				"INSERT INTO gstr2b_lines(company_id,import_id,supplier_gstin,supplier_name,doc_type,invoice_number,invoice_date,original_invoice_number,original_invoice_date,invoice_value,taxable_amount,igst_amount,cgst_amount,sgst_amount,cess_amount,itc_available,itc_unavailable_reason,reverse_charge,rate_breakup) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
				rows);
		poster.audit(companyId, "GSTR2B_IMPORTED", "GSTR2B_IMPORT", importId, period + " (" + rows.size() + " documents)");
		runReconciliation(companyId, importId);
		return summary(companyId, importId);
	}

	@GetMapping("/imports")
	public List<Map<String, Object>> imports(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(IMPORT_SELECT
				+ " WHERE company_id=? ORDER BY substring(return_period,3,4) DESC,substring(return_period,1,2) DESC",
				companyId);
	}

	@GetMapping("/imports/{importId}")
	public Map<String, Object> summary(@PathVariable long companyId, @PathVariable long importId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> found = jdbc.queryForList(IMPORT_SELECT + " WHERE company_id=? AND id=?", companyId,
				importId);
		if (found.isEmpty())
			throw new IllegalArgumentException("GSTR-2B import not found.");
		Map<String, Object> out = new LinkedHashMap<>(found.get(0));
		Map<String, Object> counts = new LinkedHashMap<>();
		for (Gstr2bMatcher.Status s : Gstr2bMatcher.Status.values())
			counts.put(s.name(), 0L);
		jdbc.query("SELECT status,count(*) FROM gstr2b_recon_items WHERE company_id=? AND import_id=? GROUP BY status",
				(RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)), companyId, importId);
		out.put("counts", counts);
		out.putAll(jdbc.queryForMap(ITC_SQL, companyId, importId));
		return out;
	}

	/** Re-runs matching (e.g. after new bills or notes are entered); existing resolutions are kept. */
	@PostMapping("/imports/{importId}/reconcile")
	@Transactional
	public Map<String, Object> reconcile(@PathVariable long companyId, @PathVariable long importId) {
		access.requireRole(companyId, EDITORS);
		runReconciliation(companyId, importId);
		poster.audit(companyId, "GSTR2B_RECONCILED", "GSTR2B_IMPORT", importId, "re-run");
		return summary(companyId, importId);
	}

	@GetMapping("/imports/{importId}/items")
	public List<Map<String, Object>> items(@PathVariable long companyId, @PathVariable long importId,
			@RequestParam(required = false) String status) {
		access.requireMembership(companyId);
		if (status == null)
			return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.import_id=? ORDER BY r.status,r.id",
					companyId, importId);
		Gstr2bMatcher.Status.valueOf(status);
		return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.import_id=? AND r.status=? ORDER BY r.id",
				companyId, importId, status);
	}

	/** Suppliers whose invoices or credit notes are in the books but not in GSTR-2B, ranked by ITC at risk. */
	@GetMapping("/imports/{importId}/vendor-follow-ups")
	public List<Map<String, Object>> vendorFollowUps(@PathVariable long companyId, @PathVariable long importId) {
		access.requireMembership(companyId);
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

	/** Documents to chase with one vendor; the caller must already have checked company access. */
	public List<Map<String, Object>> followUpDocuments(long companyId, long importId, long vendorId) {
		return jdbc.queryForList("""
				SELECT CASE WHEN r.bill_id IS NOT NULL THEN 'Invoice' ELSE 'Credit note' END AS "kind",
				 COALESCE(b.vendor_invoice_number,n.note_number) AS "number",
				 COALESCE(b.bill_date,n.note_date) AS "documentDate",
				 COALESCE(b.taxable_amount,n.taxable_amount) AS "taxable",
				 COALESCE(b.igst_amount+b.cgst_amount+b.sgst_amount,n.igst_amount+n.cgst_amount+n.sgst_amount) AS "tax",
				 COALESCE(b.total_amount,n.total_amount) AS "total"
				""" + FOLLOW_UP_FROM + " AND p.id=? ORDER BY 3,r.id", companyId, importId, vendorId);
	}

	@PostMapping("/items/{itemId}/resolve")
	@Transactional
	public Map<String, Object> resolve(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody ResolveRequest req) {
		long actor = access.requireRole(companyId, EDITORS);
		String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
		int updated = jdbc.update(
				"UPDATE gstr2b_recon_items SET resolution=?,resolution_note=?,resolved_by_user_id=?,resolved_at=now() WHERE company_id=? AND id=? AND status<>'MATCHED'",
				req.resolution(), note, actor, companyId, itemId);
		if (updated == 0)
			throw new IllegalArgumentException("Reconciliation exception not found (matched items need no resolution).");
		poster.audit(companyId, "GSTR2B_ITEM_RESOLVED", "GSTR2B_RECON_ITEM", itemId, req.resolution());
		return jdbc.queryForList(ITEM_SELECT + " WHERE r.company_id=? AND r.id=?", companyId, itemId).get(0);
	}

	@DeleteMapping("/imports/{importId}")
	@Transactional
	public Map<String, Object> delete(@PathVariable long companyId, @PathVariable long importId) {
		access.requireRole(companyId, Set.of("OWNER"));
		if (jdbc.update("DELETE FROM gstr2b_imports WHERE company_id=? AND id=?", companyId, importId) == 0)
			throw new IllegalArgumentException("GSTR-2B import not found.");
		poster.audit(companyId, "GSTR2B_IMPORT_DELETED", "GSTR2B_IMPORT", importId, "deleted");
		return Map.of("deleted", true);
	}

	public static YearMonth parsePeriod(String period) {
		if (period == null || !period.matches("(0[1-9]|1[0-2])[0-9]{4}"))
			throw new IllegalArgumentException("GSTR-2B return period (rtnprd) must be MMYYYY.");
		return YearMonth.of(Integer.parseInt(period.substring(2)), Integer.parseInt(period.substring(0, 2)));
	}

	private void parseSection(String section, JsonNode suppliers, List<Object[]> rows) {
		for (JsonNode supplier : suppliers) {
			String ctin = text(supplier, "ctin").toUpperCase(Locale.ROOT);
			if (!IndiaTax.isValidGstin(ctin))
				throw new IllegalArgumentException("Invalid supplier GSTIN in GSTR-2B: " + ctin);
			String name = truncate(supplier.path("trdnm").asText("").trim(), 200);
			for (JsonNode doc : supplier.path("cdnr".equals(section) ? "nt" : "inv")) {
				if (rows.size() >= MAX_LINES)
					throw new IllegalArgumentException("GSTR-2B has more than " + MAX_LINES + " documents.");
				String docType, number, original = null;
				LocalDate originalDate = null;
				switch (section) {
					case "cdnr" -> {
						docType = "D".equalsIgnoreCase(doc.path("typ").asText("C")) ? "DEBIT_NOTE" : "CREDIT_NOTE";
						number = text(doc, "ntnum");
					}
					case "b2ba" -> {
						docType = "AMENDMENT";
						number = text(doc, "inum");
						original = checkNumber(text(doc, "oinum"));
						originalDate = parseDate(text(doc, "oidt"));
					}
					default -> {
						docType = "INVOICE";
						number = text(doc, "inum");
					}
				}
				checkNumber(number);
				boolean itc = "Y".equalsIgnoreCase(doc.path("itcavl").asText("Y"));
				String reason = truncate(doc.path("rsn").asText("").trim(), 100);
				rows.add(new Object[] { null, null, ctin, name, docType, number, parseDate(text(doc, "dt")), original,
						originalDate, dec(doc.get("val")), head(doc, "txval"), head(doc, "igst"), head(doc, "cgst"),
						head(doc, "sgst"), head(doc, "cess"), itc, itc ? null : reason,
						"Y".equalsIgnoreCase(doc.path("rev").asText("N")), rateBreakup(doc) });
			}
		}
	}

	private void runReconciliation(long companyId, long importId) {
		List<String> found = jdbc.queryForList(
				"SELECT return_period FROM gstr2b_imports WHERE company_id=? AND id=? FOR UPDATE", String.class,
				companyId, importId);
		if (found.isEmpty())
			throw new IllegalArgumentException("GSTR-2B import not found.");
		LocalDate periodEnd = parsePeriod(found.get(0)).atEndOfMonth();
		LocalDate windowStart = periodEnd.minusMonths(18);
		LocalDate fyStart = LocalDate.of(periodEnd.getMonthValue() >= 4 ? periodEnd.getYear() : periodEnd.getYear() - 1, 4, 1);

		List<Gstr2bMatcher.SupplierLine> invoiceLines = new ArrayList<>(), creditLines = new ArrayList<>();
		jdbc.query(
				"SELECT id,supplier_gstin,invoice_number,invoice_date,taxable_amount,igst_amount,cgst_amount,sgst_amount,itc_available,reverse_charge,doc_type,original_invoice_number FROM gstr2b_lines WHERE company_id=? AND import_id=? ORDER BY id",
				(RowCallbackHandler) rs -> ("CREDIT_NOTE".equals(rs.getString(11)) ? creditLines : invoiceLines)
						.add(new Gstr2bMatcher.SupplierLine(doc(rs), rs.getBoolean(9), rs.getBoolean(10), rs.getString(12))),
				companyId, importId);

		// Late uploads can surface months later, so older unmatched documents stay matchable,
		// but only current-financial-year documents that affect ITC are reported as missing.
		Set<Long> billClaimsItc = new HashSet<>();
		List<Gstr2bMatcher.Doc> bills = jdbc.query("""
				SELECT b.id,p.gstin,b.vendor_invoice_number,b.bill_date,b.taxable_amount,b.igst_amount,b.cgst_amount,
				 b.sgst_amount,b.itc_eligible,b.reverse_charge
				FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE b.company_id=? AND p.gstin IS NOT NULL AND b.bill_date BETWEEN ? AND ?
				 AND NOT EXISTS (SELECT 1 FROM gstr2b_recon_items r WHERE r.company_id=b.company_id AND r.bill_id=b.id
				  AND r.import_id<>? AND r.status<>'MISSING_IN_2B')
				ORDER BY b.bill_date,b.id""", (rs, i) -> {
			if (rs.getBoolean(9))
				billClaimsItc.add(rs.getLong(1));
			Gstr2bMatcher.Doc d = doc(rs);
			return new Gstr2bMatcher.Doc(d.id(), d.gstin(), d.invoiceNumber(), d.date(), d.taxable(), d.igst(), d.cgst(),
					d.sgst(), rs.getBoolean(10));
		}, companyId, windowStart, periodEnd, importId);
		Set<Long> noteClaimsItc = new HashSet<>();
		List<Gstr2bMatcher.Doc> notes = jdbc.query("""
				SELECT n.id,p.gstin,n.note_number,n.note_date,n.taxable_amount,n.igst_amount,n.cgst_amount,
				 n.sgst_amount,b.itc_eligible
				FROM adjustment_notes n
				JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id
				JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND p.gstin IS NOT NULL
				 AND n.note_date BETWEEN ? AND ?
				 AND NOT EXISTS (SELECT 1 FROM gstr2b_recon_items r WHERE r.company_id=n.company_id AND r.note_id=n.id
				  AND r.import_id<>? AND r.status<>'MISSING_IN_2B')
				ORDER BY n.note_date,n.id""", (rs, i) -> {
			if (rs.getBoolean(9))
				noteClaimsItc.add(rs.getLong(1));
			return doc(rs);
		}, companyId, windowStart, periodEnd, importId);

		Map<String, Object[]> kept = new HashMap<>();
		jdbc.query(
				"SELECT line_id,bill_id,note_id,status,resolution,resolution_note,resolved_by_user_id,resolved_at FROM gstr2b_recon_items WHERE company_id=? AND import_id=? AND resolution IS NOT NULL",
				(RowCallbackHandler) rs -> kept.put(
						itemKey(rs.getObject(1, Long.class), rs.getObject(2, Long.class), rs.getObject(3, Long.class), rs.getString(4)),
						new Object[] { rs.getString(5), rs.getString(6), rs.getObject(7, Long.class),
								rs.getObject(8, OffsetDateTime.class) }),
				companyId, importId);
		jdbc.update("DELETE FROM gstr2b_recon_items WHERE company_id=? AND import_id=?", companyId, importId);

		List<Object[]> inserts = new ArrayList<>();
		for (Gstr2bMatcher.Result r : Gstr2bMatcher.match(invoiceLines, bills,
				b -> billClaimsItc.contains(b.id()) && !b.date().isBefore(fyStart)))
			inserts.add(item(companyId, importId, r, r.bookId(), null, kept));
		for (Gstr2bMatcher.Result r : Gstr2bMatcher.match(creditLines, notes,
				n -> noteClaimsItc.contains(n.id()) && !n.date().isBefore(fyStart), CN_MISSING_IN_2B, CN_MISSING_IN_BOOKS))
			inserts.add(item(companyId, importId, r, null, r.bookId(), kept));
		jdbc.batchUpdate(
				"INSERT INTO gstr2b_recon_items(company_id,import_id,line_id,bill_id,note_id,status,reasons,resolution,resolution_note,resolved_by_user_id,resolved_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
				inserts);
		jdbc.update("UPDATE gstr2b_imports SET reconciled_at=now() WHERE company_id=? AND id=?", companyId, importId);
	}

	private static Object[] item(long companyId, long importId, Gstr2bMatcher.Result r, Long billId, Long noteId,
			Map<String, Object[]> kept) {
		Object[] prev = kept.getOrDefault(itemKey(r.lineId(), billId, noteId, r.status().name()), new Object[4]);
		return new Object[] { companyId, importId, r.lineId(), billId, noteId, r.status().name(),
				String.join("; ", r.reasons()), prev[0], prev[1], prev[2], prev[3] };
	}

	private static Gstr2bMatcher.Doc doc(ResultSet rs) throws SQLException {
		return new Gstr2bMatcher.Doc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
				rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8));
	}

	private static String itemKey(Long lineId, Long billId, Long noteId, String status) {
		return lineId + "|" + billId + "|" + noteId + "|" + status;
	}

	private String rateBreakup(JsonNode doc) {
		ArrayNode out = mapper.createArrayNode();
		for (JsonNode item : doc.path("items"))
			if (item.has("rt"))
				out.addObject().put("rate", dec(item.get("rt"))).put("taxable", dec(item.get("txval")));
		return out.toString();
	}

	private static int countDocs(JsonNode section) {
		int n = 0;
		for (JsonNode supplier : section)
			for (JsonNode child : supplier)
				if (child.isArray())
					n += child.size();
		return n;
	}

	private static String checkNumber(String number) {
		if (number.length() > 50)
			throw new IllegalArgumentException("Document number too long in GSTR-2B: " + number);
		return number;
	}

	private static String text(JsonNode node, String field) {
		String v = node.path(field).asText("").trim();
		if (v.isEmpty())
			throw new IllegalArgumentException("GSTR-2B entry is missing '" + field + "'.");
		return v;
	}

	private static BigDecimal dec(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull())
			return BigDecimal.ZERO;
		if (n.isNumber())
			return n.decimalValue();
		try {
			return new BigDecimal(n.asText().trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid amount in GSTR-2B: " + n.asText());
		}
	}

	/** Document-level totals when present, otherwise the sum of item rows. */
	private static BigDecimal head(JsonNode doc, String field) {
		if (doc.has(field))
			return dec(doc.get(field));
		BigDecimal total = BigDecimal.ZERO;
		for (JsonNode item : doc.path("items"))
			total = total.add(dec(item.get(field)));
		return total;
	}

	private static LocalDate parseDate(String value) {
		try {
			return LocalDate.parse(value, PORTAL_DATE);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("Invalid date in GSTR-2B (expected DD-MM-YYYY): " + value);
		}
	}

	private static String truncate(String s, int max) {
		return s.isEmpty() ? null : s.length() > max ? s.substring(0, max) : s;
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
