package com.financeerp.india;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Validation shared by GST sales invoices and purchase bills. */
@Component
public class GstSupport {
	public static final int MAX_LINES = 50;
	private final JdbcTemplate jdbc;

	public GstSupport(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record CompanyProfile(String gstin, String stateCode) {
		public boolean gstRegistered() {
			return gstin != null;
		}
	}

	public CompanyProfile companyProfile(long companyId) {
		return jdbc.query("SELECT gstin,state_code FROM companies WHERE id=?", rs -> {
			if (!rs.next())
				throw new IllegalArgumentException("Company not found.");
			String state = rs.getString("state_code");
			if (state == null)
				throw new IllegalArgumentException("Set the company's India GST profile (state) first.");
			return new CompanyProfile(rs.getString("gstin"), state.trim());
		}, companyId);
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
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true AND account_type=?",
				Integer.class, companyId, accountId, type);
		if (n == null || n == 0)
			throw new IllegalArgumentException("Account " + accountId + " must be an active " + type
					+ " account of this company.");
	}

	public java.util.Map<String, Object> party(long companyId, long partyId, String type) {
		java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id,name,gstin,state_code AS \"stateCode\" FROM parties WHERE company_id=? AND id=? AND party_type=? AND active=true",
				companyId, partyId, type);
		if (rows.isEmpty())
			throw new IllegalArgumentException(type.toLowerCase() + " not found or inactive.");
		return rows.get(0);
	}

	public int nextNumber(long companyId, String docType, String fiscalYear) {
		Integer n = jdbc.queryForObject(
				"INSERT INTO document_sequences(company_id,doc_type,fiscal_year,last_number) VALUES (?,?,?,1) ON CONFLICT (company_id,doc_type,fiscal_year) DO UPDATE SET last_number=document_sequences.last_number+1 RETURNING last_number",
				Integer.class, companyId, docType, fiscalYear);
		return n == null ? 1 : n;
	}
}
