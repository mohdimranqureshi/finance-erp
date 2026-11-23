package com.financeerp.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Book depreciation in the style of Companies Act 2013 Schedule II (SLM or WDV), pro-rated by days within the Indian
 * financial year (1 April - 31 March, 365 or 366 days). Pure calculation, no I/O.
 *
 * <p>
 * Amounts are worked out as a cumulative schedule: {@link #accumulatedTo} gives total depreciation from the start up to
 * a date, and a period charge is the difference from what has already been charged. Rounding (2 decimals, HALF_UP)
 * therefore never drifts across many monthly runs.
 */
public final class Depreciation {
	public enum Method {
		SLM, WDV
	}

	public static final BigDecimal DEFAULT_RESIDUAL_RATE = new BigDecimal("0.05");
	private static final int SCALE = 10;

	public record Category(String code, String name, Integer usefulLifeYears, String basis) {
	}

	/** Indicative Schedule II Part C useful lives; reference data that must be verified by a Chartered Accountant. */
	public static final List<Category> CATEGORIES = List.of(
			new Category("BUILDING_FACTORY", "Factory buildings", 30, "Schedule II Part C - Buildings (factory)"),
			new Category("BUILDING_OTHER", "Buildings other than factory buildings (RCC frame)", 60,
					"Schedule II Part C - Buildings (non-factory, RCC frame structure)"),
			new Category("PLANT_MACHINERY", "Plant and machinery (general)", 15,
					"Schedule II Part C - Plant and machinery, general rate"),
			new Category("FURNITURE", "Furniture and fittings", 10, "Schedule II Part C - Furniture and fittings"),
			new Category("MOTOR_VEHICLE", "Motor cars and vehicles (not run on hire)", 8,
					"Schedule II Part C - Motor vehicles other than those used in a business of running them on hire"),
			new Category("COMPUTER", "Computers and end-user devices (desktops, laptops)", 3,
					"Schedule II Part C - Computers, end user devices"),
			new Category("SERVER", "Servers and networks", 6, "Schedule II Part C - Computers, servers and networks"),
			new Category("OFFICE_EQUIPMENT", "Office equipment", 5, "Schedule II Part C - Office equipment"),
			new Category("ELECTRICAL_INSTALLATION", "Electrical installations and equipment", 10,
					"Schedule II Part C - Electrical installations and equipment"),
			new Category("OTHER", "Other (useful life must be supplied)", null, "Assessed by management"));

	public static final String CATEGORY_NOTE = "Useful lives are indicative Companies Act 2013 Schedule II reference data. "
			+ "Verify the useful life, residual value and method with a qualified Chartered Accountant before use. "
			+ "This is book (Companies Act) depreciation, not Income-tax Act block depreciation.";

	public static Optional<Category> category(String code) {
		return CATEGORIES.stream().filter(c -> c.code().equals(code)).findFirst();
	}

	/** Parameters of one asset. {@code startDate} is the first day still to depreciate after any opening balance. */
	public record Asset(BigDecimal cost, BigDecimal residual, Method method, int usefulLifeYears, BigDecimal wdvRate,
			LocalDate startDate, BigDecimal openingAccumulated, LocalDate disposalDate) {
		public Asset {
			if (cost == null || cost.signum() <= 0)
				throw new IllegalArgumentException("Cost must be greater than zero.");
			if (residual == null || residual.signum() < 0 || residual.compareTo(cost) > 0)
				throw new IllegalArgumentException("Residual value must be between 0 and the cost.");
			if (usefulLifeYears < 1)
				throw new IllegalArgumentException("Useful life must be at least one year.");
			if (method == Method.WDV && (wdvRate == null || wdvRate.signum() <= 0 || wdvRate.compareTo(BigDecimal.ONE) >= 0))
				throw new IllegalArgumentException("WDV rate must be between 0 and 1.");
			openingAccumulated = openingAccumulated == null ? BigDecimal.ZERO : openingAccumulated;
			if (openingAccumulated.signum() < 0 || openingAccumulated.compareTo(cost.subtract(residual)) > 0)
				throw new IllegalArgumentException(
						"Opening accumulated depreciation must be between 0 and cost less residual value.");
		}

		public BigDecimal depreciableAmount() {
			return cost.subtract(residual);
		}
	}

	private Depreciation() {
	}

	/** 5% of cost, rounded to 2 decimals. */
	public static BigDecimal defaultResidual(BigDecimal cost) {
		return cost.multiply(DEFAULT_RESIDUAL_RATE).setScale(2, RoundingMode.HALF_UP);
	}

	public static BigDecimal slmAnnual(BigDecimal cost, BigDecimal residual, int usefulLifeYears) {
		return cost.subtract(residual).divide(BigDecimal.valueOf(usefulLifeYears), SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * WDV rate = 1 - (residual / cost) ^ (1 / life), rounded to 6 decimals. A zero residual would give a 100% rate, so
	 * a notional residual of 1 rupee is used for the rate only; the floor stays at the actual (zero) residual.
	 */
	public static BigDecimal wdvRate(BigDecimal cost, BigDecimal residual, int usefulLifeYears) {
		if (cost.signum() <= 0 || usefulLifeYears < 1)
			throw new IllegalArgumentException("Cost and useful life must be positive.");
		BigDecimal notional = residual.signum() == 0 ? BigDecimal.ONE.min(cost) : residual;
		if (notional.compareTo(cost) >= 0)
			throw new IllegalArgumentException("Residual value must be below cost for the WDV method.");
		double ratio = notional.divide(cost, MathContext.DECIMAL64).doubleValue();
		BigDecimal rate = BigDecimal.valueOf(1 - Math.pow(ratio, 1.0 / usefulLifeYears)).setScale(6, RoundingMode.HALF_UP);
		if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0)
			throw new IllegalArgumentException("Cannot derive a WDV rate from this cost, residual value and life.");
		return rate;
	}

	public static LocalDate financialYearEnd(LocalDate date) {
		int endYear = date.getMonthValue() >= 4 ? date.getYear() + 1 : date.getYear();
		return LocalDate.of(endYear, Month.MARCH, 31);
	}

	public static int daysInFinancialYear(LocalDate date) {
		LocalDate end = financialYearEnd(date);
		return (int) ChronoUnit.DAYS.between(end.minusYears(1), end);
	}

	/** Last day that may be depreciated: the day before disposal, otherwise {@code asOf}. */
	private static LocalDate cap(Asset a, LocalDate asOf) {
		if (a.disposalDate() != null && !a.disposalDate().minusDays(1).isAfter(asOf))
			return a.disposalDate().minusDays(1);
		return asOf;
	}

	/** Total accumulated depreciation (including the opening balance) at the end of {@code asOf}, 2 decimals. */
	public static BigDecimal accumulatedTo(Asset a, LocalDate asOf) {
		LocalDate end = cap(a, asOf);
		BigDecimal limit = a.depreciableAmount();
		BigDecimal acc = a.openingAccumulated();
		BigDecimal annualSlm = a.method() == Method.SLM ? slmAnnual(a.cost(), a.residual(), a.usefulLifeYears()) : null;
		LocalDate cursor = a.startDate();
		while (!cursor.isAfter(end) && acc.compareTo(limit) < 0) {
			LocalDate segmentEnd = financialYearEnd(cursor);
			if (segmentEnd.isAfter(end))
				segmentEnd = end;
			BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(cursor, segmentEnd) + 1);
			BigDecimal yearDays = BigDecimal.valueOf(daysInFinancialYear(cursor));
			// WDV: the opening written-down value of the financial year (or of the asset's first day in it).
			BigDecimal annual = a.method() == Method.SLM ? annualSlm : a.cost().subtract(acc).multiply(a.wdvRate());
			BigDecimal charge = annual.multiply(days).divide(yearDays, SCALE, RoundingMode.HALF_UP);
			acc = acc.add(charge).min(limit);
			cursor = segmentEnd.plusDays(1);
		}
		return acc.setScale(2, RoundingMode.HALF_UP);
	}

	/** Charge for the period ending {@code to}, given what has already been charged (never negative). */
	public static BigDecimal chargeTo(Asset a, BigDecimal alreadyAccumulated, LocalDate to) {
		BigDecimal charge = accumulatedTo(a, to).subtract(alreadyAccumulated);
		return charge.signum() > 0 ? charge.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
	}

	public static Map<String, Object> describe(Category c) {
		java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("code", c.code());
		m.put("name", c.name());
		m.put("usefulLifeYears", c.usefulLifeYears());
		m.put("basis", c.basis());
		return m;
	}
}
