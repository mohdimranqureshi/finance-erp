package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.dto.IndiaSetupDto.ProfileRequest;
import com.financeerp.repository.IndiaSetupRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class IndiaSetupService {
	private final IndiaSetupRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;

	public IndiaSetupService(IndiaSetupRepository repository, CompanyAccess access, LedgerPoster poster) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
	}

	// code, name, type, system key (nullable)
	private static final String[][] CHART = { { "1000", "Cash in Hand", "ASSET", null },
			{ "1010", "Bank Accounts", "ASSET", "BANK" }, { "1100", "Trade Receivables", "ASSET", "AR" },
			{ "1200", "GST Input Credit - CGST", "ASSET", "INPUT_CGST" },
			{ "1210", "GST Input Credit - SGST", "ASSET", "INPUT_SGST" },
			{ "1220", "GST Input Credit - IGST", "ASSET", "INPUT_IGST" },
			{ "1230", "TDS Receivable", "ASSET", "TDS_RECEIVABLE" },
			{ "1500", "Property, Plant and Equipment", "ASSET", "FIXED_ASSETS" },
			{ "1590", "Accumulated Depreciation", "ASSET", "ACCUMULATED_DEPRECIATION" },
			{ "2000", "Trade Payables", "LIABILITY", "AP" },
			{ "2100", "GST Output - CGST", "LIABILITY", "OUTPUT_CGST" },
			{ "2110", "GST Output - SGST", "LIABILITY", "OUTPUT_SGST" },
			{ "2120", "GST Output - IGST", "LIABILITY", "OUTPUT_IGST" },
			{ "2130", "GST Payable under Reverse Charge - CGST", "LIABILITY", "RCM_CGST" },
			{ "2140", "GST Payable under Reverse Charge - SGST", "LIABILITY", "RCM_SGST" },
			{ "2150", "GST Payable under Reverse Charge - IGST", "LIABILITY", "RCM_IGST" },
			{ "2200", "TDS Payable", "LIABILITY", "TDS_PAYABLE" },
			{ "2210", "TCS Payable", "LIABILITY", "TCS_PAYABLE" },
			{ "2300", "Deferred Revenue", "LIABILITY", "DEFERRED_REVENUE" }, { "3000", "Share Capital", "EQUITY", null },
			{ "3100", "Reserves and Surplus", "EQUITY", null },
			{ "3900", "Opening Balance Adjustment", "EQUITY", "OPENING_BALANCE" },
			{ "4000", "Revenue from Operations", "INCOME", "SALES" },
			{ "4050", "Sales Returns and Allowances", "INCOME", "SALES_RETURNS" },
			{ "4900", "Other Income", "INCOME", null },
			{ "4910", "Profit on Disposal of Fixed Assets", "INCOME", "ASSET_DISPOSAL_GAIN" },
			{ "5000", "Purchases and Cost of Services", "EXPENSE", "PURCHASES" },
			{ "5100", "Employee Benefit Expenses", "EXPENSE", null }, { "5200", "Rent", "EXPENSE", null },
			{ "5300", "Professional and Legal Fees", "EXPENSE", null },
			{ "5400", "Depreciation and Amortisation", "EXPENSE", "DEPRECIATION" },
			{ "5900", "Other Expenses", "EXPENSE", null },
			{ "5910", "Loss on Disposal of Fixed Assets", "EXPENSE", "ASSET_DISPOSAL_LOSS" } };

	public Map<String, Object> reference() {
		List<Map<String, String>> states = new ArrayList<>();
		IndiaTax.STATES.forEach((code, name) -> states.add(Map.of("code", code, "name", name)));
		return Map.of("states", states, "gstRates", IndiaTax.allowedGstRates(java.time.LocalDate.now()), "tdsSections",
				IndiaTax.TDS_SECTIONS, "tcsSections", IndiaTax.TCS_SECTIONS, "note",
				"Rates are indicative reference data. Confirm GST/TDS treatment with a qualified Indian tax professional.");
	}

	public Map<String, Object> profile(long companyId) {
		access.requireMembership(companyId);
		return repository.findProfile(companyId);
	}

	@Transactional
	public Map<String, Object> updateProfile(long companyId, ProfileRequest req) {
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
		String tan = blankToNull(req.tan());
		if (tan != null) {
			tan = tan.toUpperCase();
			if (!tan.matches("[A-Z]{4}[0-9]{5}[A-Z]"))
				throw new IllegalArgumentException("TAN is invalid (format AAAA99999A).");
		}
		repository.updateProfile(companyId, gstin, pan, state, blankToNull(req.addressLine()), blankToNull(req.city()),
				blankToNull(req.pincode()));
		// Clients that do not send "tan" leave it unchanged; an empty string clears it.
		if (req.tan() != null)
			repository.updateTan(companyId, tan);
		poster.audit(companyId, "INDIA_PROFILE_UPDATED", "COMPANY", companyId, "GST profile updated");
		return profile(companyId);
	}

	@Transactional
	public Map<String, Object> seedChart(long companyId) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		int created = 0;
		for (String[] a : CHART)
			created += repository.insertAccountIfAbsent(companyId, a[0], a[1], a[2], a[3]);
		// Charts seeded before a system key existed (e.g. 5400 Depreciation) adopt the key when the type matches.
		for (String[] a : CHART)
			if (a[3] != null)
				repository.adoptSystemKey(companyId, a[0], a[2], a[3]);
		for (String[] a : CHART)
			if (a[3] != null) {
				if (repository.countBySystemKey(companyId, a[3]) == 0)
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
