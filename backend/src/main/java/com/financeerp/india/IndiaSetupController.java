package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class IndiaSetupController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;

	public IndiaSetupController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
	}

	public record ProfileRequest(String gstin, String pan, @NotBlank String stateCode, @Size(max = 200) String addressLine,
			@Size(max = 100) String city, @Pattern(regexp = "([1-9][0-9]{5})?") String pincode) {
	}

	// code, name, type, system key (nullable)
	private static final String[][] CHART = { { "1000", "Cash in Hand", "ASSET", null },
			{ "1010", "Bank Accounts", "ASSET", "BANK" }, { "1100", "Trade Receivables", "ASSET", "AR" },
			{ "1200", "GST Input Credit - CGST", "ASSET", "INPUT_CGST" },
			{ "1210", "GST Input Credit - SGST", "ASSET", "INPUT_SGST" },
			{ "1220", "GST Input Credit - IGST", "ASSET", "INPUT_IGST" },
			{ "1230", "TDS Receivable", "ASSET", "TDS_RECEIVABLE" }, { "2000", "Trade Payables", "LIABILITY", "AP" },
			{ "2100", "GST Output - CGST", "LIABILITY", "OUTPUT_CGST" },
			{ "2110", "GST Output - SGST", "LIABILITY", "OUTPUT_SGST" },
			{ "2120", "GST Output - IGST", "LIABILITY", "OUTPUT_IGST" },
			{ "2200", "TDS Payable", "LIABILITY", "TDS_PAYABLE" },
			{ "2300", "Deferred Revenue", "LIABILITY", "DEFERRED_REVENUE" }, { "3000", "Share Capital", "EQUITY", null },
			{ "3100", "Reserves and Surplus", "EQUITY", null },
			{ "4000", "Revenue from Operations", "INCOME", "SALES" },
			{ "4050", "Sales Returns and Allowances", "INCOME", "SALES_RETURNS" },
			{ "4900", "Other Income", "INCOME", null },
			{ "5000", "Purchases and Cost of Services", "EXPENSE", "PURCHASES" },
			{ "5100", "Employee Benefit Expenses", "EXPENSE", null }, { "5200", "Rent", "EXPENSE", null },
			{ "5300", "Professional and Legal Fees", "EXPENSE", null },
			{ "5400", "Depreciation and Amortisation", "EXPENSE", null }, { "5900", "Other Expenses", "EXPENSE", null } };

	@GetMapping("/api/v1/india/reference")
	public Map<String, Object> reference() {
		List<Map<String, String>> states = new ArrayList<>();
		IndiaTax.STATES.forEach((code, name) -> states.add(Map.of("code", code, "name", name)));
		return Map.of("states", states, "gstRates", IndiaTax.allowedGstRates(java.time.LocalDate.now()), "tdsSections",
				IndiaTax.TDS_SECTIONS, "note",
				"Rates are indicative reference data. Confirm GST/TDS treatment with a qualified Indian tax professional.");
	}

	@GetMapping("/api/v1/companies/{companyId}/india/profile")
	public Map<String, Object> profile(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForMap(
				"SELECT gstin,pan,state_code AS \"stateCode\",address_line AS \"addressLine\",city,pincode FROM companies WHERE id=?", companyId);
	}

	@PutMapping("/api/v1/companies/{companyId}/india/profile")
	@Transactional
	public Map<String, Object> updateProfile(@PathVariable long companyId, @Valid @RequestBody ProfileRequest req) {
		access.requireRole(companyId, Set.of("OWNER"));
		String state = req.stateCode().trim();
		String gstin = blankToNull(req.gstin());
		String pan = blankToNull(req.pan());
		if (!IndiaTax.STATES.containsKey(state))
			throw new IllegalArgumentException("Unknown GST state code.");
		if (gstin != null) {
			gstin = gstin.toUpperCase();
			if (!IndiaTax.isValidGstin(gstin))
				throw new IllegalArgumentException("GSTIN is invalid (format or checksum).");
			if (!gstin.startsWith(state))
				throw new IllegalArgumentException("GSTIN state code must match the selected state.");
		}
		if (pan != null) {
			pan = pan.toUpperCase();
			if (!IndiaTax.isValidPan(pan))
				throw new IllegalArgumentException("PAN is invalid.");
			if (gstin != null && !gstin.substring(2, 12).equals(pan))
				throw new IllegalArgumentException("PAN must match characters 3-12 of the GSTIN.");
		}
		jdbc.update("UPDATE companies SET gstin=?,pan=?,state_code=?,address_line=?,city=?,pincode=? WHERE id=?", gstin, pan,
				state, blankToNull(req.addressLine()), blankToNull(req.city()), blankToNull(req.pincode()), companyId);
		poster.audit(companyId, "INDIA_PROFILE_UPDATED", "COMPANY", companyId, "GST profile updated");
		return profile(companyId);
	}

	@PostMapping("/api/v1/companies/{companyId}/india/chart-of-accounts")
	@Transactional
	public Map<String, Object> seedChart(@PathVariable long companyId) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		int created = 0;
		for (String[] a : CHART)
			created += jdbc.update(
					"INSERT INTO accounts(company_id,code,name,account_type,system_key) VALUES (?,?,?,?,?) ON CONFLICT (company_id,code) DO NOTHING",
					companyId, a[0], a[1], a[2], a[3]);
		for (String[] a : CHART)
			if (a[3] != null) {
				Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND system_key=?",
						Integer.class, companyId, a[3]);
				if (n == null || n == 0)
					throw new IllegalArgumentException("Account code " + a[0]
							+ " already exists as a custom account; rename or re-code it before running setup.");
			}
		poster.audit(companyId, "INDIA_CHART_SEEDED", "COMPANY", companyId, created + " accounts created");
		return Map.of("created", created);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
