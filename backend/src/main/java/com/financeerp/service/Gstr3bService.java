package com.financeerp.service;

import com.financeerp.domain.Gstr3bSetOff;
import com.financeerp.repository.Gstr3bRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * GSTR-3B working sheet for one month, computed from posted, non-reversed documents (and the month's GSTR-2B if
 * imported). It is a review aid, not a filing: the return is filed on the GST portal or through a GSP.
 */
@Service
public class Gstr3bService {
	private final Gstr3bRepository repository;
	private final CompanyAccess access;

	public Gstr3bService(Gstr3bRepository repository, CompanyAccess access) {
		this.repository = repository;
		this.access = access;
	}

	/** period is MMYYYY; itcBasis is "books" (default) or "gstr2b" (forward-charge ITC as available in GSTR-2B). */
	public Map<String, Object> gstr3b(long companyId, String period, String itcBasis) {
		access.requireMembership(companyId);
		if (!itcBasis.equals("books") && !itcBasis.equals("gstr2b"))
			throw new IllegalArgumentException("itcBasis must be 'books' or 'gstr2b'.");
		YearMonth month = Gstr2bService.parsePeriod(period);
		LocalDate from = month.atDay(1), to = month.atEndOfMonth();
		List<String> warnings = new ArrayList<>();

		Map<String, Object> taxableOutward = repository.outwardTaxable(companyId, from, to);
		Map<String, Object> creditNotes = repository.salesCreditNotes(companyId, from, to);
		BigDecimal nilRated = repository.nilRatedTaxable(companyId, from, to);
		List<Map<String, Object>> unregisteredInterState = repository.unregisteredInterState(companyId, from, to);
		Map<String, Object> rcm = repository.reverseChargeBills(companyId, from, to);
		Map<String, Object> rcmItc = repository.reverseChargeItcBills(companyId, from, to);
		Map<String, Object> forwardItcBooks = repository.forwardChargeItcBills(companyId, from, to);
		Map<String, Object> ineligible = repository.ineligibleItcBills(companyId, from, to);
		Map<String, Object> supplierCreditNotes = repository.supplierCreditNotes(companyId, from, to);

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

	/** Forward-charge ITC available in the month's GSTR-2B (credit notes reduce it), or null if not imported. */
	private Map<String, Object> gstr2bItc(long companyId, String period) {
		Integer imported = repository.countGstr2bImports(companyId, period);
		if (imported == null || imported == 0)
			return null;
		return repository.gstr2bAvailableItc(companyId, period);
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
