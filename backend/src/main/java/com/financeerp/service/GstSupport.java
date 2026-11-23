package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.repository.GstRepository;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Validation shared by GST sales invoices and purchase bills. */
@Service
public class GstSupport {
	public static final int MAX_LINES = 50;
	private final GstRepository repository;

	public GstSupport(GstRepository repository) {
		this.repository = repository;
	}

	public record CompanyProfile(String gstin, String stateCode) {
		public boolean gstRegistered() {
			return gstin != null;
		}
	}

	public CompanyProfile companyProfile(long companyId) {
		GstRepository.GstProfileRow row = repository.findGstProfile(companyId)
				.orElseThrow(() -> new IllegalArgumentException("Company not found."));
		if (row.stateCode() == null)
			throw new IllegalArgumentException("Set the company's India GST profile (state) first.");
		return new CompanyProfile(row.gstin(), row.stateCode().trim());
	}

	/** Fits NUMERIC(19,4) with headroom for multi-line totals and tax. */
	public void checkAmount(BigDecimal value, int maxIntegerDigits, String label) {
		if (value.signum() < 0 || value.scale() > 4 || value.precision() - value.scale() > maxIntegerDigits)
			throw new IllegalArgumentException(label + " must be non-negative, have at most four decimal places and at most "
					+ maxIntegerDigits + " integer digits.");
	}

	public void checkLine(BigDecimal quantity, BigDecimal unitPrice, BigDecimal gstRate, String hsnSac, LocalDate date,
			boolean taxApplicable) {
		checkAmount(quantity, 6, "Quantity");
		checkAmount(unitPrice, 7, "Unit price");
		if (quantity.signum() == 0 || unitPrice.signum() == 0)
			throw new IllegalArgumentException("Quantity and unit price must be greater than zero.");
		if (!hsnSac.matches("\\d{4}|\\d{6}|\\d{8}"))
			throw new IllegalArgumentException("HSN/SAC must be 4, 6 or 8 digits.");
		if (!IndiaTax.isAllowedGstRate(gstRate, date))
			throw new IllegalArgumentException(
					"GST rate " + gstRate.stripTrailingZeros().toPlainString() + "% is not valid for " + date + ".");
		if (!taxApplicable && gstRate.signum() != 0)
			throw new IllegalArgumentException("GST cannot be charged by an unregistered party; use a 0% rate.");
	}

	public void requireAccount(long companyId, Long accountId, String type) {
		if (repository.countActiveAccountOfType(companyId, accountId, type) == 0)
			throw new IllegalArgumentException("Account " + accountId + " must be an active " + type
					+ " account of this company.");
	}

	public Map<String, Object> party(long companyId, long partyId, String type) {
		List<Map<String, Object>> rows = repository.findActiveParty(companyId, partyId, type);
		if (rows.isEmpty())
			throw new IllegalArgumentException(type.toLowerCase() + " not found or inactive.");
		return rows.get(0);
	}

	public int nextNumber(long companyId, String docType, String fiscalYear) {
		return repository.nextDocumentNumber(companyId, docType, fiscalYear);
	}
}