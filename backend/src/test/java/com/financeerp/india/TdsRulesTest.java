package com.financeerp.india;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TdsRulesTest {
	@Test
	void depositIsDueOnTheSeventhExceptMarchDeductions() {
		assertThat(TdsRules.depositDueDate(LocalDate.of(2026, 5, 20))).isEqualTo(LocalDate.of(2026, 6, 7));
		assertThat(TdsRules.depositDueDate(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 7));
		assertThat(TdsRules.depositDueDate(LocalDate.of(2027, 3, 15))).isEqualTo(LocalDate.of(2027, 4, 30));
	}

	@Test
	void lateDepositInterestCountsEachCalendarMonthOrPart() {
		BigDecimal tds = new BigDecimal("10000");
		assertThat(TdsRules.lateDepositInterest(tds, LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 7))).isEqualByComparingTo("0");
		// May and June: 2 months at 1.5%.
		assertThat(TdsRules.lateDepositInterest(tds, LocalDate.of(2026, 5, 20), LocalDate.of(2026, 6, 8))).isEqualByComparingTo("300");
		assertThat(TdsRules.lateDepositInterest(tds, LocalDate.of(2026, 11, 30), LocalDate.of(2027, 2, 1))).isEqualByComparingTo("600");
	}

	@Test
	void quartersAndReturnDueDatesFollowTheIndianFinancialYear() {
		assertThat(TdsRules.quarter(LocalDate.of(2026, 4, 1))).isEqualTo(1);
		assertThat(TdsRules.quarter(LocalDate.of(2026, 9, 30))).isEqualTo(2);
		assertThat(TdsRules.quarter(LocalDate.of(2026, 12, 1))).isEqualTo(3);
		assertThat(TdsRules.quarter(LocalDate.of(2027, 3, 31))).isEqualTo(4);
		assertThat(TdsRules.quarterStart("2026-27", 4)).isEqualTo(LocalDate.of(2027, 1, 1));
		assertThat(TdsRules.returnDueDate("2026-27", 1)).isEqualTo(LocalDate.of(2026, 7, 31));
		assertThat(TdsRules.returnDueDate("2026-27", 3)).isEqualTo(LocalDate.of(2027, 1, 31));
		assertThat(TdsRules.returnDueDate("2026-27", 4)).isEqualTo(LocalDate.of(2027, 5, 31));
		assertThatThrownBy(() -> TdsRules.quarterStart("2026-28", 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tcsIsRoundedToWholeRupeesOnTheGstInclusiveValue() {
		assertThat(IndiaTax.tcs(new BigDecimal("118050.00"), new BigDecimal("1"))).isEqualByComparingTo("1181");
		assertThat(IndiaTax.isTcsSection("1-SCRAP")).isTrue();
		assertThat(IndiaTax.isTcsSection("206C1H")).isFalse();
	}
}
