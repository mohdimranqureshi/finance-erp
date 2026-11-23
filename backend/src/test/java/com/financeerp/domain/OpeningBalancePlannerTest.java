package com.financeerp.domain;

import com.financeerp.domain.OpeningBalancePlanner.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpeningBalancePlannerTest {
	private static final LocalDate AS_OF = LocalDate.of(2026, 4, 1);
	private static final Accounts ACCOUNTS = new Accounts(11, 22, 39);
	private static final long BANK = 10, LOAN = 25;

	private static BigDecimal d(String v) {
		return new BigDecimal(v);
	}

	private static GlLine dr(long account, String amount) {
		return new GlLine(account, d(amount), null, null);
	}

	private static GlLine cr(long account, String amount) {
		return new GlLine(account, BigDecimal.ZERO, d(amount), "memo");
	}

	private static PartyItem item(long party, String number, String amount) {
		return new PartyItem(party, number, AS_OF.minusDays(10), AS_OF.plusDays(20), d(amount));
	}

	private static BigDecimal sum(List<LedgerLine> lines, boolean debit) {
		return lines.stream().map(l -> debit ? l.debit() : l.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@Test
	void postsPartyTotalsToArApAndCreditsTheDifferenceToOpeningEquity() {
		Plan p = OpeningBalancePlanner.plan(AS_OF, List.of(dr(BANK, "50000"), cr(LOAN, "20000")),
				List.of(item(1, "INV-1", "10000"), item(1, "INV-2", "2500.5"), item(2, "INV-1", "500")),
				List.of(item(3, "B-9", "7000")), ACCOUNTS);

		assertThat(p.receivableTotal()).isEqualByComparingTo("13000.5");
		assertThat(p.payableTotal()).isEqualByComparingTo("7000");
		assertThat(p.equityAdjustment()).isEqualByComparingTo("36000.5");
		assertThat(p.lines()).hasSize(5);
		assertThat(p.lines()).anySatisfy(l -> {
			assertThat(l.accountId()).isEqualTo(11L);
			assertThat(l.debit()).isEqualByComparingTo("13000.5");
		});
		assertThat(p.lines()).anySatisfy(l -> {
			assertThat(l.accountId()).isEqualTo(22L);
			assertThat(l.credit()).isEqualByComparingTo("7000");
		});
		assertThat(p.lines().get(4).accountId()).isEqualTo(39L);
		assertThat(p.lines().get(4).credit()).isEqualByComparingTo("36000.5");
		assertThat(p.lines().get(0).description()).isEqualTo(OpeningBalancePlanner.DEFAULT_MEMO);
		assertThat(sum(p.lines(), true)).isEqualByComparingTo(sum(p.lines(), false));
	}

	@Test
	void debitsOpeningEquityWhenCreditsExceedDebitsAndOmitsItWhenBalanced() {
		Plan deficit = OpeningBalancePlanner.plan(AS_OF, List.of(cr(LOAN, "900")), List.of(),
				List.of(item(3, "B-1", "100")), ACCOUNTS);
		assertThat(deficit.equityAdjustment()).isEqualByComparingTo("-1000");
		LedgerLine equity = deficit.lines().get(deficit.lines().size() - 1);
		assertThat(equity.accountId()).isEqualTo(39L);
		assertThat(equity.debit()).isEqualByComparingTo("1000");
		assertThat(equity.credit()).isZero();

		Plan balanced = OpeningBalancePlanner.plan(AS_OF, List.of(dr(BANK, "100"), cr(LOAN, "100")), null, null, ACCOUNTS);
		assertThat(balanced.lines()).hasSize(2).noneMatch(l -> l.accountId() == 39L);
		assertThat(balanced.equityAdjustment()).isZero();
	}

	@Test
	void rejectsGlLinesOnTradeReceivablesOrPayables() {
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(dr(11, "100")), null, null, ACCOUNTS))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 1").hasMessageContaining("Receivables/Payables");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(dr(BANK, "100"), cr(22, "100")), null, null, ACCOUNTS))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 2");
	}

	@Test
	void rejectsMalformedGlLines() {
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(new GlLine(BANK, d("10"), d("10"), null)), null, null, ACCOUNTS))
				.hasMessageContaining("exactly one positive");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(new GlLine(BANK, null, null, null)), null, null, ACCOUNTS))
				.hasMessageContaining("exactly one positive");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(dr(BANK, "-5")), null, null, ACCOUNTS))
				.hasMessageContaining("non-negative");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, List.of(new GlLine(null, d("5"), null, null)), null, null, ACCOUNTS))
				.hasMessageContaining("accountId");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null, List.of(), null, ACCOUNTS))
				.hasMessageContaining("at least one");
	}

	@Test
	void validatesPartyItemAmountsAndDates() {
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null, List.of(item(1, "A", "0")), null, ACCOUNTS))
				.hasMessageContaining("greater than zero");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null, List.of(item(1, "A", "1.00001")), null, ACCOUNTS))
				.hasMessageContaining("four decimal places");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null, null,
				List.of(new PartyItem(3L, "B", AS_OF.plusDays(1), null, d("5"))), ACCOUNTS))
				.hasMessageContaining("Payable 1").hasMessageContaining("after the opening balance date");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null,
				List.of(new PartyItem(1L, "A", AS_OF, AS_OF.minusDays(1), d("5"))), null, ACCOUNTS))
				.hasMessageContaining("dueDate");
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null, List.of(item(1, "  ", "5")), null, ACCOUNTS))
				.hasMessageContaining("documentNumber");

		Plan ok = OpeningBalancePlanner.plan(AS_OF, null,
				List.of(new PartyItem(1L, " A-1 ", AS_OF, null, d("1.50000"))), null, ACCOUNTS);
		assertThat(ok.receivables().get(0).documentNumber()).isEqualTo("A-1");
	}

	@Test
	void rejectsDuplicateDocumentNumbersPerPartyButAllowsThemAcrossParties() {
		assertThatThrownBy(() -> OpeningBalancePlanner.plan(AS_OF, null,
				List.of(item(1, "inv-7", "10"), item(1, " INV-7", "20")), null, ACCOUNTS))
				.hasMessageContaining("Receivable 2").hasMessageContaining("repeated");
		assertThat(OpeningBalancePlanner.plan(AS_OF, null, List.of(item(1, "INV-7", "10"), item(2, "INV-7", "20")),
				null, ACCOUNTS).receivableTotal()).isEqualByComparingTo("30");
	}
}
