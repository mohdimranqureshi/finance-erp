package com.financeerp.india;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GSTR-3B Table 6.1 style ITC set-off (CGST Act sections 49, 49A, 49B and Rule 88A): IGST credit is used first,
 * against IGST and then CGST/SGST; CGST credit against CGST then IGST; SGST credit against SGST then IGST. CGST
 * credit can never pay SGST and vice versa. Reverse-charge tax is paid in cash and is not part of this set-off.
 */
public final class Gstr3bSetOff {
	public record Heads(BigDecimal igst, BigDecimal cgst, BigDecimal sgst) {
		public static final Heads ZERO = new Heads(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

		public Heads plus(Heads o) {
			return new Heads(igst.add(o.igst), cgst.add(o.cgst), sgst.add(o.sgst));
		}

		public Heads minus(Heads o) {
			return new Heads(igst.subtract(o.igst), cgst.subtract(o.cgst), sgst.subtract(o.sgst));
		}

		/** Negative heads become zero (e.g. more credit notes than supplies in a month). */
		public Heads floorZero() {
			return new Heads(igst.max(BigDecimal.ZERO), cgst.max(BigDecimal.ZERO), sgst.max(BigDecimal.ZERO));
		}

		public BigDecimal total() {
			return igst.add(cgst).add(sgst);
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("igst", igst);
			m.put("cgst", cgst);
			m.put("sgst", sgst);
			m.put("total", total());
			return m;
		}
	}

	/** usedFrom[credit head][liability head]; cash = liability left after credit; carryForward = unused credit. */
	public record Result(Map<String, Map<String, BigDecimal>> usedFrom, Heads cash, Heads carryForward) {
	}

	private Gstr3bSetOff() {
	}

	public static Result setOff(Heads liability, Heads credit) {
		if (liability.igst().signum() < 0 || liability.cgst().signum() < 0 || liability.sgst().signum() < 0
				|| credit.igst().signum() < 0 || credit.cgst().signum() < 0 || credit.sgst().signum() < 0)
			throw new IllegalArgumentException("Liability and credit must not be negative.");
		BigDecimal[] owe = { liability.igst(), liability.cgst(), liability.sgst() };
		BigDecimal[] have = { credit.igst(), credit.cgst(), credit.sgst() };
		String[] names = { "igst", "cgst", "sgst" };
		Map<String, Map<String, BigDecimal>> used = new LinkedHashMap<>();
		for (String n : names) {
			Map<String, BigDecimal> row = new LinkedHashMap<>();
			for (String m : names)
				row.put(m, BigDecimal.ZERO);
			used.put(n, row);
		}
		// Indexes: 0=IGST, 1=CGST, 2=SGST.
		apply(0, 0, have, owe, used, names, null);
		// IGST credit may go to CGST and SGST in any proportion: first cover what their own credit cannot.
		apply(0, 1, have, owe, used, names, owe[1].subtract(have[1]).max(BigDecimal.ZERO));
		apply(0, 2, have, owe, used, names, owe[2].subtract(have[2]).max(BigDecimal.ZERO));
		// Rule 88A: IGST credit must be fully used before CGST/SGST credit.
		apply(0, 1, have, owe, used, names, null);
		apply(0, 2, have, owe, used, names, null);
		apply(1, 1, have, owe, used, names, null);
		apply(1, 0, have, owe, used, names, null);
		apply(2, 2, have, owe, used, names, null);
		apply(2, 0, have, owe, used, names, null);
		return new Result(used, new Heads(owe[0], owe[1], owe[2]), new Heads(have[0], have[1], have[2]));
	}

	private static void apply(int from, int to, BigDecimal[] have, BigDecimal[] owe, Map<String, Map<String, BigDecimal>> used,
			String[] names, BigDecimal cap) {
		BigDecimal amount = have[from].min(owe[to]);
		if (cap != null)
			amount = amount.min(cap);
		if (amount.signum() <= 0)
			return;
		have[from] = have[from].subtract(amount);
		owe[to] = owe[to].subtract(amount);
		used.get(names[from]).merge(names[to], amount, BigDecimal::add);
	}
}
