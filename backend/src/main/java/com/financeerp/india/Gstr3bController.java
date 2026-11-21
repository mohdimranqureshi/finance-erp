package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * GSTR-3B working sheet for one month, computed from posted, non-reversed documents (and the month's GSTR-2B if
 * imported). It is a review aid, not a filing: the return is filed on the GST portal or through a GSP.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/gst")
public class Gstr3bController {
	private static final String POSTED = """
			JOIN journal_entries j ON j.company_id=%1$s.company_id AND j.id=%1$s.journal_entry_id AND j.status='POSTED'
			LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
			""";
	private static final String NOT_REVERSED = " AND (r.id IS NULL OR r.status<>'POSTED')";
	private static final String HEADS = """
			COALESCE(SUM(%1$s.taxable_amount),0) AS taxable,COALESCE(SUM(%1$s.igst_amount),0) AS igst,
			COALESCE(SUM(%1$s.cgst_amount),0) AS cgst,COALESCE(SUM(%1$s.sgst_amount),0) AS sgst""";

	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public Gstr3bController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	/** period is MMYYYY; itcBasis is "books" (default) or "gstr2b" (forward-charge ITC as available in GSTR-2B). */
	@GetMapping("/gstr3b")
	public Map<String, Object> gstr3b(@PathVariable long companyId, @RequestParam String period,
			@RequestParam(defaultValue = "books") String itcBasis) {
		access.requireMembership(companyId);
		if (!itcBasis.equals("books") && !itcBasis.equals("gstr2b"))
			throw new IllegalArgumentException("itcBasis must be 'books' or 'gstr2b'.");
		YearMonth month = Gstr2bController.parsePeriod(period);
		LocalDate from = month.atDay(1), to = month.atEndOfMonth();
		List<String> warnings = new ArrayList<>();

		Map<String, Object> taxableOutward = jdbc.queryForMap("SELECT " + HEADS.formatted("l") + """
				 FROM sales_invoices i JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				""" + POSTED.formatted("i") + " WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND l.gst_rate>0"
				+ NOT_REVERSED, companyId, from, to);
		Map<String, Object> creditNotes = jdbc.queryForMap("SELECT " + HEADS.formatted("n") + " FROM adjustment_notes n "
				+ POSTED.formatted("n") + " WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?"
				+ NOT_REVERSED, companyId, from, to);
		BigDecimal nilRated = jdbc.queryForObject("SELECT COALESCE(SUM(l.taxable_amount),0) FROM sales_invoices i "
				+ "JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id " + POSTED.formatted("i")
				+ " WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND l.gst_rate=0" + NOT_REVERSED,
				BigDecimal.class, companyId, from, to);
		List<Map<String, Object>> unregisteredInterState = jdbc.queryForList("SELECT i.place_of_supply AS \"placeOfSupply\","
				+ HEADS.formatted("l") + """
				 FROM sales_invoices i JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				 JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				""" + POSTED.formatted("i") + """
				 WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND p.gstin IS NULL
				 AND i.supply_type='INTER_STATE' AND l.gst_rate>0""" + NOT_REVERSED + """
				 GROUP BY i.place_of_supply ORDER BY i.place_of_supply""", companyId, from, to);
		Map<String, Object> rcm = bills(companyId, from, to, "b.reverse_charge");
		Map<String, Object> rcmItc = bills(companyId, from, to, "b.reverse_charge AND b.itc_eligible");
		Map<String, Object> forwardItcBooks = bills(companyId, from, to, "NOT b.reverse_charge AND b.itc_eligible");
		Map<String, Object> ineligible = bills(companyId, from, to,
				"NOT b.itc_eligible AND b.cgst_amount+b.sgst_amount+b.igst_amount>0");
		Map<String, Object> supplierCreditNotes = jdbc.queryForMap("SELECT " + HEADS.formatted("n") + """
				 FROM adjustment_notes n
				 JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id AND b.itc_eligible
				""" + POSTED.formatted("n") + " WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND n.note_date BETWEEN ? AND ?"
				+ NOT_REVERSED, companyId, from, to);

		Map<String, Object> gstr2b = gstr2bItc(companyId, period);
		Gstr3bSetOff.Heads forwardItc = heads(forwardItcBooks).minus(heads(supplierCreditNotes));
		if (gstr2b != null) {
			Gstr3bSetOff.Heads available = heads(gstr2b);
			if (forwardItc.total().compareTo(available.total()) > 0)
				warnings.add("ITC in the books exceeds ITC available in GSTR-2B by "
						+ forwardItc.total().subtract(available.total()).toPlainString()
						+ ". Rule 36(4) allows only ITC reflected in GSTR-2B; consider itcBasis=gstr2b.");
			if (itcBasis.equals("gstr2b"))
				forwardItc = available;
		} else {
			if (itcBasis.equals("gstr2b"))
				throw new IllegalArgumentException("Import the GSTR-2B for " + period + " before using itcBasis=gstr2b.");
			warnings.add("GSTR-2B for " + period + " has not been imported, so ITC is taken from the books unverified.");
		}

		Gstr3bSetOff.Heads outwardTax = heads(taxableOutward).minus(heads(creditNotes));
		if (outwardTax.igst().signum() < 0 || outwardTax.cgst().signum() < 0 || outwardTax.sgst().signum() < 0)
			warnings.add("Credit notes exceed this month's outward tax for a head; the excess is not carried forward here.");
		Gstr3bSetOff.Heads netItc = heads(rcmItc).plus(forwardItc);
		if (netItc.igst().signum() < 0 || netItc.cgst().signum() < 0 || netItc.sgst().signum() < 0)
			warnings.add("Supplier credit notes exceed this month's ITC for a head; reverse the excess in Table 4(B)(2).");
		Gstr3bSetOff.Result setOff = Gstr3bSetOff.setOff(outwardTax.floorZero(), netItc.floorZero());
		Gstr3bSetOff.Heads rcmCash = heads(rcm);

		Map<String, Object> t31 = new LinkedHashMap<>();
		t31.put("a_outwardTaxable", withTaxable(outwardTax, taxable(taxableOutward).subtract(taxable(creditNotes))));
		t31.put("c_nilRatedExempt", Map.of("taxable", nilRated));
		t31.put("d_inwardReverseCharge", withTaxable(rcmCash, taxable(rcm)));
		Map<String, Object> t4 = new LinkedHashMap<>();
		t4.put("a3_inwardReverseCharge", heads(rcmItc).toMap());
		t4.put("a5_allOtherItc", forwardItc.toMap());
		t4.put("a5_supplierCreditNotesNetted", heads(supplierCreditNotes).toMap());
		t4.put("c_netItcAvailable", netItc.toMap());
		t4.put("d2_ineligibleItc", heads(ineligible).toMap());
		Map<String, Object> t61 = new LinkedHashMap<>();
		t61.put("liability", outwardTax.floorZero().toMap());
		t61.put("itcUsed", setOff.usedFrom());
		t61.put("cashForForwardCharge", setOff.cash().toMap());
		t61.put("cashForReverseCharge", rcmCash.toMap());
		t61.put("totalCash", setOff.cash().plus(rcmCash).toMap());
		t61.put("itcCarriedForward", setOff.carryForward().toMap());

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("period", period);
		out.put("itcBasis", itcBasis);
		out.put("table3_1", t31);
		out.put("table3_2_interStateToUnregistered", unregisteredInterState);
		out.put("table4", t4);
		if (gstr2b != null)
			out.put("gstr2bAvailableItc", heads(gstr2b).toMap());
		out.put("table6_1", t61);
		out.put("warnings", warnings);
		out.put("note", "Working sheet only. Cess, interest, late fee, Rule 42/43 reversals, blocked credits under section 17(5), "
				+ "exports/SEZ and composition/UIN supplies are not computed. Verify with a GST practitioner before filing.");
		return out;
	}

	private Map<String, Object> bills(long companyId, LocalDate from, LocalDate to, String condition) {
		return jdbc.queryForMap("SELECT " + HEADS.formatted("b") + " FROM purchase_bills b " + POSTED.formatted("b")
				+ " WHERE b.company_id=? AND b.bill_date BETWEEN ? AND ? AND " + condition + NOT_REVERSED, companyId, from, to);
	}

	/** Forward-charge ITC available in the month's GSTR-2B (credit notes reduce it), or null if not imported. */
	private Map<String, Object> gstr2bItc(long companyId, String period) {
		Integer imported = jdbc.queryForObject("SELECT count(*) FROM gstr2b_imports WHERE company_id=? AND return_period=?",
				Integer.class, companyId, period);
		if (imported == null || imported == 0)
			return null;
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.taxable_amount ELSE l.taxable_amount END),0) AS taxable,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.igst_amount ELSE l.igst_amount END),0) AS igst,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.cgst_amount ELSE l.cgst_amount END),0) AS cgst,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.sgst_amount ELSE l.sgst_amount END),0) AS sgst
				FROM gstr2b_imports i
				JOIN gstr2b_lines l ON l.company_id=i.company_id AND l.import_id=i.id
				WHERE i.company_id=? AND i.return_period=? AND l.itc_available AND NOT l.reverse_charge
				""", companyId, period);
	}

	private static Gstr3bSetOff.Heads heads(Map<String, Object> row) {
		return new Gstr3bSetOff.Heads((BigDecimal) row.get("igst"), (BigDecimal) row.get("cgst"), (BigDecimal) row.get("sgst"));
	}

	private static BigDecimal taxable(Map<String, Object> row) {
		return (BigDecimal) row.get("taxable");
	}

	private static Map<String, Object> withTaxable(Gstr3bSetOff.Heads heads, BigDecimal taxable) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("taxable", taxable);
		m.putAll(heads.toMap());
		return m;
	}
}
