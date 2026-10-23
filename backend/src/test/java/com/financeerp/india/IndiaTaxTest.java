package com.financeerp.india;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class IndiaTaxTest {
	@Test
	void gstinChecksumIsVerified() {
		assertThat(IndiaTax.isValidGstin("27AAPFU0939F1ZV")).isTrue();
		assertThat(IndiaTax.isValidGstin("29AAPFU0939F1ZR")).isTrue();
		assertThat(IndiaTax.isValidGstin("27AAPFU0939F1ZX")).isFalse();
		assertThat(IndiaTax.isValidGstin("99AAPFU0939F1ZV")).isFalse();
		assertThat(IndiaTax.isValidGstin("short")).isFalse();
	}

	@Test
	void intraStateSplitsIntoEqualCgstAndSgst() {
		IndiaTax.Taxed t = IndiaTax.computeLine(new BigDecimal("2"), new BigDecimal("5000"), new BigDecimal("18"), false);
		assertThat(t.taxable()).isEqualByComparingTo("10000.00");
		assertThat(t.cgst()).isEqualByComparingTo("900.00");
		assertThat(t.sgst()).isEqualByComparingTo("900.00");
		assertThat(t.igst()).isEqualByComparingTo("0");
		assertThat(t.total()).isEqualByComparingTo("11800.00");
	}

	@Test
	void interStateChargesIgstOnly() {
		IndiaTax.Taxed t = IndiaTax.computeLine(BigDecimal.ONE, new BigDecimal("10000"), new BigDecimal("18"), true);
		assertThat(t.igst()).isEqualByComparingTo("1800.00");
		assertThat(t.cgst().add(t.sgst())).isEqualByComparingTo("0");
	}

	@Test
	void gstSlabsAreEffectiveDated() {
		assertThat(IndiaTax.isAllowedGstRate(new BigDecimal("12"), LocalDate.of(2025, 6, 1))).isTrue();
		assertThat(IndiaTax.isAllowedGstRate(new BigDecimal("12"), LocalDate.of(2026, 1, 1))).isFalse();
		assertThat(IndiaTax.isAllowedGstRate(new BigDecimal("40"), LocalDate.of(2026, 1, 1))).isTrue();
		assertThat(IndiaTax.isAllowedGstRate(new BigDecimal("7"), LocalDate.of(2026, 1, 1))).isFalse();
	}

	@Test
	void tdsIsRoundedToWholeRupeesAndFiscalYearStartsInApril() {
		assertThat(IndiaTax.tds(new BigDecimal("12345.50"), new BigDecimal("10"))).isEqualByComparingTo("1235");
		assertThat(IndiaTax.fiscalYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
		assertThat(IndiaTax.fiscalYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
	}
}
