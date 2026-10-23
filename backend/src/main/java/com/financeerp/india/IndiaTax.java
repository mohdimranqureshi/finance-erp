package com.financeerp.india;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Indian GST/TDS rules. Effective-dated values must be reviewed by a qualified tax professional. */
public final class IndiaTax {
	private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
	private static final LocalDate GST_RATE_RESTRUCTURE = LocalDate.of(2025, 9, 22);
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal TWO_HUNDRED = BigDecimal.valueOf(200);

	public static final Map<String, String> STATES = new LinkedHashMap<>();
	static {
		String[] s = { "01", "Jammu and Kashmir", "02", "Himachal Pradesh", "03", "Punjab", "04", "Chandigarh", "05",
				"Uttarakhand", "06", "Haryana", "07", "Delhi", "08", "Rajasthan", "09", "Uttar Pradesh", "10", "Bihar",
				"11", "Sikkim", "12", "Arunachal Pradesh", "13", "Nagaland", "14", "Manipur", "15", "Mizoram", "16",
				"Tripura", "17", "Meghalaya", "18", "Assam", "19", "West Bengal", "20", "Jharkhand", "21", "Odisha",
				"22", "Chhattisgarh", "23", "Madhya Pradesh", "24", "Gujarat", "26",
				"Dadra and Nagar Haveli and Daman and Diu", "27", "Maharashtra", "29", "Karnataka", "30", "Goa", "31",
				"Lakshadweep", "32", "Kerala", "33", "Tamil Nadu", "34", "Puducherry", "35",
				"Andaman and Nicobar Islands", "36", "Telangana", "37", "Andhra Pradesh", "38", "Ladakh", "97",
				"Other Territory" };
		for (int i = 0; i < s.length; i += 2)
			STATES.put(s[i], s[i + 1]);
	}

	/** Indicative TDS sections and rates; always verify the current rate before deducting. */
	public static final List<Map<String, Object>> TDS_SECTIONS = List.of(
			Map.of("section", "194C", "description", "Contractors", "indicativeRate", 1),
			Map.of("section", "194H", "description", "Commission or brokerage", "indicativeRate", 2),
			Map.of("section", "194I", "description", "Rent (land, building)", "indicativeRate", 10),
			Map.of("section", "194J", "description", "Professional or technical fees", "indicativeRate", 10),
			Map.of("section", "194A", "description", "Interest other than securities", "indicativeRate", 10));

	private IndiaTax() {
	}

	public record Taxed(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
		public BigDecimal tax() {
			return cgst.add(sgst).add(igst);
		}

		public BigDecimal total() {
			return taxable.add(tax());
		}
	}

	public static boolean isValidGstin(String gstin) {
		if (gstin == null || !GSTIN.matcher(gstin).matches() || !STATES.containsKey(gstin.substring(0, 2)))
			return false;
		int sum = 0;
		for (int i = 0; i < 14; i++) {
			int product = BASE36.indexOf(gstin.charAt(i)) * (i % 2 == 0 ? 1 : 2);
			sum += product / 36 + product % 36;
		}
		return BASE36.charAt((36 - sum % 36) % 36) == gstin.charAt(14);
	}

	public static boolean isValidPan(String pan) {
		return pan != null && PAN.matcher(pan).matches();
	}

	public static List<BigDecimal> allowedGstRates(LocalDate date) {
		List<String> rates = date.isBefore(GST_RATE_RESTRUCTURE)
				? List.of("0", "0.1", "0.25", "1", "1.5", "3", "5", "12", "18", "28")
				: List.of("0", "0.1", "0.25", "1", "1.5", "3", "5", "18", "40");
		return rates.stream().map(BigDecimal::new).toList();
	}

	public static boolean isAllowedGstRate(BigDecimal rate, LocalDate date) {
		return allowedGstRates(date).stream().anyMatch(r -> r.compareTo(rate) == 0);
	}

	public static Taxed computeLine(BigDecimal quantity, BigDecimal unitPrice, BigDecimal gstRate,
			boolean interState) {
		BigDecimal taxable = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
		BigDecimal zero = BigDecimal.ZERO.setScale(2);
		if (interState)
			return new Taxed(taxable, zero, zero, taxable.multiply(gstRate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
		BigDecimal half = taxable.multiply(gstRate).divide(TWO_HUNDRED, 2, RoundingMode.HALF_UP);
		return new Taxed(taxable, half, half, zero);
	}

	/** TDS is deducted in whole rupees. */
	public static BigDecimal tds(BigDecimal taxable, BigDecimal rate) {
		return taxable.multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP).setScale(2);
	}

	/** Indian financial year label such as 2026-27 (April to March). */
	public static String fiscalYear(LocalDate date) {
		int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
		return String.format("%d-%02d", start, (start + 1) % 100);
	}
}
