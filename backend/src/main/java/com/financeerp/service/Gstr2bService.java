package com.financeerp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.financeerp.domain.Gstr2bMatcher;
import com.financeerp.domain.IndiaTax;
import com.financeerp.dto.Gstr2bDto.ResolveRequest;
import com.financeerp.repository.Gstr2bRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

@Service
public class Gstr2bService {
	private static final Set<String> EDITORS = Set.of("OWNER", "ACCOUNTANT");
	private static final Set<String> SUPPORTED_SECTIONS = Set.of("b2b", "b2ba", "cdnr");
	private static final int MAX_LINES = 50_000;
	private static final String CN_MISSING_IN_2B = "Credit note recorded in books but not in this GSTR-2B: ask the supplier to report it in GSTR-1/IFF.";
	private static final String CN_MISSING_IN_BOOKS = "Supplier credit note not recorded: reverse the ITC by recording a debit note against the original bill.";
	private static final DateTimeFormatter PORTAL_DATE = DateTimeFormatter.ofPattern("dd-MM-uuuu")
			.withResolverStyle(ResolverStyle.STRICT);

	private final Gstr2bRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;
	private final ObjectMapper mapper;

	public Gstr2bService(Gstr2bRepository repository, CompanyAccess access, LedgerPoster poster, GstSupport gst,
			ObjectMapper mapper) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
		this.mapper = mapper;
	}

	/** Accepts the GSTR-2B JSON downloaded from the GST portal (with or without the outer "data" wrapper). */
	@Transactional
	public Map<String, Object> importReturn(long companyId, JsonNode body) {
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
		Integer existing = repository.countImportsForPeriod(companyId, period);
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

		Long importId = repository.insertImport(companyId, period, recipient, sha256(body.toString()), rows.size(),
				skipped, actor);
		for (Object[] r : rows) {
			r[0] = companyId;
			r[1] = importId;
		}
		repository.insertLines(rows);
		poster.audit(companyId, "GSTR2B_IMPORTED", "GSTR2B_IMPORT", importId, period + " (" + rows.size() + " documents)");
		runReconciliation(companyId, importId);
		return summary(companyId, importId);
	}

	public List<Map<String, Object>> imports(long companyId) {
		access.requireMembership(companyId);
		return repository.findImports(companyId);
	}

	public Map<String, Object> summary(long companyId, long importId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> found = repository.findImport(companyId, importId);
		if (found.isEmpty())
			throw new IllegalArgumentException("GSTR-2B import not found.");
		Map<String, Object> out = new LinkedHashMap<>(found.get(0));
		Map<String, Object> counts = new LinkedHashMap<>();
		for (Gstr2bMatcher.Status s : Gstr2bMatcher.Status.values())
			counts.put(s.name(), 0L);
		counts.putAll(repository.countItemsByStatus(companyId, importId));
		out.put("counts", counts);
		out.putAll(repository.findItcSummary(companyId, importId));
		return out;
	}

	/** Re-runs matching (e.g. after new bills or notes are entered); existing resolutions are kept. */
	@Transactional
	public Map<String, Object> reconcile(long companyId, long importId) {
		access.requireRole(companyId, EDITORS);
		runReconciliation(companyId, importId);
		poster.audit(companyId, "GSTR2B_RECONCILED", "GSTR2B_IMPORT", importId, "re-run");
		return summary(companyId, importId);
	}

	public List<Map<String, Object>> items(long companyId, long importId, String status) {
		access.requireMembership(companyId);
		if (status == null)
			return repository.findItems(companyId, importId);
		Gstr2bMatcher.Status.valueOf(status);
		return repository.findItemsByStatus(companyId, importId, status);
	}

	/** Suppliers whose invoices or credit notes are in the books but not in GSTR-2B, ranked by ITC at risk. */
	public List<Map<String, Object>> vendorFollowUps(long companyId, long importId) {
		access.requireMembership(companyId);
		return repository.findVendorFollowUps(companyId, importId);
	}

	/** Documents to chase with one vendor; the caller must already have checked company access. */
	public List<Map<String, Object>> followUpDocuments(long companyId, long importId, long vendorId) {
		return repository.findFollowUpDocuments(companyId, importId, vendorId);
	}

	@Transactional
	public Map<String, Object> resolve(long companyId, long itemId, ResolveRequest req) {
		long actor = access.requireRole(companyId, EDITORS);
		String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
		int updated = repository.resolveItem(companyId, itemId, req.resolution(), note, actor);
		if (updated == 0)
			throw new IllegalArgumentException("Reconciliation exception not found (matched items need no resolution).");
		poster.audit(companyId, "GSTR2B_ITEM_RESOLVED", "GSTR2B_RECON_ITEM", itemId, req.resolution());
		return repository.findItem(companyId, itemId).get(0);
	}

	@Transactional
	public Map<String, Object> delete(long companyId, long importId) {
		access.requireRole(companyId, Set.of("OWNER"));
		if (repository.deleteImport(companyId, importId) == 0)
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
		List<String> found = repository.lockImportPeriod(companyId, importId);
		if (found.isEmpty())
			throw new IllegalArgumentException("GSTR-2B import not found.");
		LocalDate periodEnd = parsePeriod(found.get(0)).atEndOfMonth();
		LocalDate windowStart = periodEnd.minusMonths(18);
		LocalDate fyStart = LocalDate.of(periodEnd.getMonthValue() >= 4 ? periodEnd.getYear() : periodEnd.getYear() - 1, 4, 1);

		List<Gstr2bMatcher.SupplierLine> invoiceLines = new ArrayList<>(), creditLines = new ArrayList<>();
		for (Gstr2bRepository.PortalLine p : repository.findPortalLines(companyId, importId))
			("CREDIT_NOTE".equals(p.docType()) ? creditLines : invoiceLines).add(p.line());

		// Late uploads can surface months later, so older unmatched documents stay matchable,
		// but only current-financial-year documents that affect ITC are reported as missing.
		Set<Long> billClaimsItc = new HashSet<>();
		List<Gstr2bMatcher.Doc> bills = new ArrayList<>();
		for (Gstr2bRepository.BookDoc b : repository.findBillsForMatching(companyId, windowStart, periodEnd, importId)) {
			if (b.claimsItc())
				billClaimsItc.add(b.doc().id());
			bills.add(b.doc());
		}
		Set<Long> noteClaimsItc = new HashSet<>();
		List<Gstr2bMatcher.Doc> notes = new ArrayList<>();
		for (Gstr2bRepository.BookDoc n : repository.findPurchaseDebitNotesForMatching(companyId, windowStart, periodEnd,
				importId)) {
			if (n.claimsItc())
				noteClaimsItc.add(n.doc().id());
			notes.add(n.doc());
		}

		Map<String, Object[]> kept = new HashMap<>();
		for (Gstr2bRepository.KeptResolution k : repository.findKeptResolutions(companyId, importId))
			kept.put(itemKey(k.lineId(), k.billId(), k.noteId(), k.status()),
					new Object[] { k.resolution(), k.note(), k.resolvedByUserId(), k.resolvedAt() });
		repository.deleteReconItems(companyId, importId);

		List<Object[]> inserts = new ArrayList<>();
		for (Gstr2bMatcher.Result r : Gstr2bMatcher.match(invoiceLines, bills,
				b -> billClaimsItc.contains(b.id()) && !b.date().isBefore(fyStart)))
			inserts.add(item(companyId, importId, r, r.bookId(), null, kept));
		for (Gstr2bMatcher.Result r : Gstr2bMatcher.match(creditLines, notes,
				n -> noteClaimsItc.contains(n.id()) && !n.date().isBefore(fyStart), CN_MISSING_IN_2B, CN_MISSING_IN_BOOKS))
			inserts.add(item(companyId, importId, r, null, r.bookId(), kept));
		repository.insertReconItems(inserts);
		repository.markReconciled(companyId, importId);
	}

	private static Object[] item(long companyId, long importId, Gstr2bMatcher.Result r, Long billId, Long noteId,
			Map<String, Object[]> kept) {
		Object[] prev = kept.getOrDefault(itemKey(r.lineId(), billId, noteId, r.status().name()), new Object[4]);
		return new Object[] { companyId, importId, r.lineId(), billId, noteId, r.status().name(),
				String.join("; ", r.reasons()), prev[0], prev[1], prev[2], prev[3] };
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
