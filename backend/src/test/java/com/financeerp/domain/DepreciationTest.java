package com.financeerp.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepreciationTest {
	private static final BigDecimal COST = new BigDecimal("100000.00");
	private static final BigDecimal RESIDUAL = new BigDecimal("5000.00");

	private static Depreciation.Asset slm(LocalDate start, LocalDate disposal) {
		return new Depreciation.Asset(COST, RESIDUAL, Depreciation.Method.SLM, 5, null, start, null, disposal);
	}

	private static Depreciation.Asset wdv(LocalDate start) {
		return new Depreciation.Asset(COST, RESIDUAL, Depreciation.Method.WDV, 3, Depreciation.wdvRate(COST, RESIDUAL, 3),
				start, null, null);
	}

	private static LocalDate d(String iso) {
		return LocalDate.parse(iso);
	}

	@Test
	void defaultResidualIsFivePercentRounded() {
		assertThat(Depreciation.defaultResidual(new BigDecimal("12345.67"))).isEqualByComparingTo("617.28");
	}

	@Test
	void slmChargesEqualAnnualAmountsForFullYears() {
		assertThat(Depreciation.slmAnnual(COST, RESIDUAL, 5)).isEqualByComparingTo("19000");
		Depreciation.Asset a = slm(d("2024-04-01"), null);
		assertThat(Depreciation.accumulatedTo(a, d("2025-03-31"))).isEqualByComparingTo("19000.00");
		assertThat(Depreciation.accumulatedTo(a, d("2026-03-31"))).isEqualByComparingTo("38000.00");
		assertThat(Depreciation.accumulatedTo(a, d("2024-03-31"))).isEqualByComparingTo("0");
	}

	@Test
	void slmIsProRatedByDaysFromPutToUseDate() {
		// 1 Oct 2024 - 31 Mar 2025 = 182 of 365 days.
		Depreciation.Asset a = slm(d("2024-10-01"), null);
		assertThat(Depreciation.accumulatedTo(a, d("2025-03-31"))).isEqualByComparingTo("9473.97");
		assertThat(Depreciation.accumulatedTo(a, d("2024-10-01"))).isEqualByComparingTo("52.05");
	}

	@Test
	void leapFinancialYearUses366Days() {
		assertThat(Depreciation.daysInFinancialYear(d("2023-06-01"))).isEqualTo(366);
		assertThat(Depreciation.daysInFinancialYear(d("2024-06-01"))).isEqualTo(365);
		assertThat(Depreciation.financialYearEnd(d("2024-02-29"))).isEqualTo(d("2024-03-31"));
		// 1 Jan - 31 Mar 2024 = 91 of 366 days.
		assertThat(Depreciation.accumulatedTo(slm(d("2024-01-01"), null), d("2024-03-31"))).isEqualByComparingTo("4724.04");
		// A full leap financial year still charges exactly the annual amount.
		assertThat(Depreciation.accumulatedTo(slm(d("2023-04-01"), null), d("2024-03-31"))).isEqualByComparingTo("19000.00");
	}

	@Test
	void monthlyChargesAddUpToTheAnnualChargeWithoutRoundingDrift() {
		for (Depreciation.Asset a : new Depreciation.Asset[] { slm(d("2023-04-01"), null), wdv(d("2023-04-01")) }) {
			BigDecimal accumulated = BigDecimal.ZERO;
			for (LocalDate end = d("2023-04-30"); !end.isAfter(d("2024-03-31")); end = end.plusDays(1).plusMonths(1).minusDays(1))
				accumulated = accumulated.add(Depreciation.chargeTo(a, accumulated, end));
			assertThat(accumulated).isEqualByComparingTo(Depreciation.accumulatedTo(a, d("2024-03-31")));
		}
	}

	@Test
	void slmNeverDepreciatesBelowResidual() {
		Depreciation.Asset a = slm(d("2020-10-01"), null);
		assertThat(Depreciation.accumulatedTo(a, d("2035-03-31"))).isEqualByComparingTo("95000.00");
		assertThat(Depreciation.chargeTo(a, new BigDecimal("95000"), d("2036-03-31"))).isEqualByComparingTo("0");
	}

	@Test
	void wdvRateFollowsScheduleTwoFormula() {
		assertThat(Depreciation.wdvRate(COST, RESIDUAL, 3)).isEqualByComparingTo("0.631597");
		// Zero residual: a notional residual of 1 rupee is used for the rate.
		assertThat(Depreciation.wdvRate(COST, BigDecimal.ZERO, 5)).isEqualByComparingTo("0.900000");
		assertThatThrownBy(() -> Depreciation.wdvRate(COST, COST, 5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void wdvAppliesRateToOpeningWrittenDownValueEachYear() {
		Depreciation.Asset a = wdv(d("2024-04-01"));
		assertThat(Depreciation.accumulatedTo(a, d("2025-03-31"))).isEqualByComparingTo("63159.70");
		assertThat(Depreciation.accumulatedTo(a, d("2026-03-31"))).isEqualByComparingTo("86427.92");
		assertThat(Depreciation.accumulatedTo(a, d("2040-03-31"))).isEqualByComparingTo("95000.00");
	}

	@Test
	void wdvIsProRatedInTheFirstYear() {
		Depreciation.Asset a = wdv(d("2024-10-01"));
		assertThat(Depreciation.accumulatedTo(a, d("2025-03-31"))).isEqualByComparingTo("31493.33");
		assertThat(Depreciation.accumulatedTo(a, d("2026-03-31"))).isEqualByComparingTo("74761.94");
	}

	@Test
	void depreciationStopsTheDayBeforeDisposal() {
		Depreciation.Asset disposed = slm(d("2024-04-01"), d("2025-01-01"));
		BigDecimal toDisposal = Depreciation.accumulatedTo(slm(d("2024-04-01"), null), d("2024-12-31"));
		assertThat(Depreciation.accumulatedTo(disposed, d("2027-03-31"))).isEqualByComparingTo(toDisposal);
		assertThat(Depreciation.chargeTo(disposed, toDisposal, d("2025-03-31"))).isEqualByComparingTo("0");
	}

	@Test
	void broughtForwardAssetContinuesFromOpeningBalance() {
		Depreciation.Asset a = new Depreciation.Asset(COST, RESIDUAL, Depreciation.Method.SLM, 5, null, d("2024-04-01"),
				new BigDecimal("38000"), null);
		assertThat(Depreciation.accumulatedTo(a, d("2024-03-31"))).isEqualByComparingTo("38000.00");
		assertThat(Depreciation.chargeTo(a, new BigDecimal("38000"), d("2025-03-31"))).isEqualByComparingTo("19000.00");
	}

	@Test
	void invalidParametersAreRejected() {
		assertThatThrownBy(() -> new Depreciation.Asset(COST, COST.add(BigDecimal.ONE), Depreciation.Method.SLM, 5, null,
				d("2024-04-01"), null, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Depreciation.Asset(COST, RESIDUAL, Depreciation.Method.WDV, 5, null, d("2024-04-01"),
				null, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Depreciation.Asset(COST, RESIDUAL, Depreciation.Method.SLM, 5, null, d("2024-04-01"),
				new BigDecimal("95000.01"), null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void categoriesCarryIndicativeUsefulLives() {
		assertThat(Depreciation.category("COMPUTER").orElseThrow().usefulLifeYears()).isEqualTo(3);
		assertThat(Depreciation.category("SERVER").orElseThrow().usefulLifeYears()).isEqualTo(6);
		assertThat(Depreciation.category("OTHER").orElseThrow().usefulLifeYears()).isNull();
		assertThat(Depreciation.category("NOPE")).isEmpty();
	}
}
