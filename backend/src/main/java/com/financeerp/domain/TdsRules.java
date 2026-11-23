package com.financeerp.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

/** Indicative TDS timing rules (Income-tax Act s.200, s.201(1A), Rule 30/31A); confirm with a tax professional. */
public final class TdsRules {
	private static final BigDecimal LATE_DEPOSIT_RATE_PER_MONTH = new BigDecimal("1.5");

	private TdsRules() {
	}

	/** Non-government deductors deposit by the 7th of the next month; March deductions by 30 April. */
	public static LocalDate depositDueDate(LocalDate deductionDate) {
		if (deductionDate.getMonth() == Month.MARCH)
			return LocalDate.of(deductionDate.getYear(), 4, 30);
		return YearMonth.from(deductionDate).plusMonths(1).atDay(7);
	}

	/**
	 * Interest under s.201(1A)(ii) for late deposit: 1.5% per month or part of a month from the date of deduction to
	 * the date of deposit, counting calendar months. Zero when deposited on time.
	 */
	public static BigDecimal lateDepositInterest(BigDecimal tds, LocalDate deductionDate, LocalDate depositDate) {
		if (!depositDate.isAfter(depositDueDate(deductionDate)))
			return BigDecimal.ZERO.setScale(2);
		long months = YearMonth.from(depositDate).getMonthValue() + 12L * depositDate.getYear()
				- (YearMonth.from(deductionDate).getMonthValue() + 12L * deductionDate.getYear()) + 1;
		return tds.multiply(LATE_DEPOSIT_RATE_PER_MONTH).multiply(BigDecimal.valueOf(months))
				.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).setScale(2);
	}

	/** Quarter 1-4 of the Indian financial year (Q1 = April to June). */
	public static int quarter(LocalDate date) {
		return (date.getMonthValue() + 8) % 12 / 3 + 1;
	}

	public static LocalDate quarterStart(String fiscalYear, int quarter) {
		if (fiscalYear == null || !fiscalYear.matches("\\d{4}-\\d{2}"))
			throw new IllegalArgumentException("Financial year must look like 2026-27.");
		int start = Integer.parseInt(fiscalYear.substring(0, 4));
		if (!IndiaTax.fiscalYear(LocalDate.of(start, 4, 1)).equals(fiscalYear))
			throw new IllegalArgumentException("Financial year must look like 2026-27.");
		if (quarter < 1 || quarter > 4)
			throw new IllegalArgumentException("Quarter must be 1 to 4.");
		return LocalDate.of(start, 4, 1).plusMonths(3L * (quarter - 1));
	}

	/** Form 26Q due dates: 31 Jul, 31 Oct, 31 Jan and 31 May. */
	public static LocalDate returnDueDate(String fiscalYear, int quarter) {
		LocalDate start = quarterStart(fiscalYear, quarter);
		return quarter == 4 ? LocalDate.of(start.getYear(), 5, 31) : start.plusMonths(4).minusDays(1);
	}
}
