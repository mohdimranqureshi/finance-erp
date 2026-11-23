package com.financeerp.domain;

import com.financeerp.domain.Gstr3bSetOff.Heads;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gstr3bSetOffTest {
	private static Heads h(int igst, int cgst, int sgst) {
		return new Heads(BigDecimal.valueOf(igst), BigDecimal.valueOf(cgst), BigDecimal.valueOf(sgst));
	}

	private static void assertHeads(Heads actual, int igst, int cgst, int sgst) {
		assertThat(actual.igst()).isEqualByComparingTo(BigDecimal.valueOf(igst));
		assertThat(actual.cgst()).isEqualByComparingTo(BigDecimal.valueOf(cgst));
		assertThat(actual.sgst()).isEqualByComparingTo(BigDecimal.valueOf(sgst));
	}

	@Test
	void ownHeadCreditsThenCrossUtilisationAgainstIgst() {
		Gstr3bSetOff.Result r = Gstr3bSetOff.setOff(h(100, 50, 50), h(30, 60, 10));
		assertHeads(r.cash(), 60, 0, 40);
		assertHeads(r.carryForward(), 0, 0, 0);
		assertThat(r.usedFrom().get("cgst").get("igst")).isEqualByComparingTo("10");
	}

	@Test
	void igstCreditIsSplitToAvoidCashWhereOwnCreditFallsShort() {
		assertHeads(Gstr3bSetOff.setOff(h(0, 100, 100), h(100, 100, 0)).cash(), 0, 0, 0);
		assertHeads(Gstr3bSetOff.setOff(h(0, 100, 100), h(100, 0, 100)).cash(), 0, 0, 0);
	}

	@Test
	void igstCreditIsExhaustedBeforeCgstAndSgstCredit() {
		Gstr3bSetOff.Result r = Gstr3bSetOff.setOff(h(0, 100, 100), h(300, 100, 100));
		assertHeads(r.cash(), 0, 0, 0);
		assertHeads(r.carryForward(), 100, 100, 100);
		assertThat(r.usedFrom().get("cgst").get("cgst")).isEqualByComparingTo("0");
	}

	@Test
	void cgstCreditNeverPaysSgst() {
		Gstr3bSetOff.Result r = Gstr3bSetOff.setOff(h(0, 0, 100), h(0, 100, 0));
		assertHeads(r.cash(), 0, 0, 100);
		assertHeads(r.carryForward(), 0, 100, 0);
	}

	@Test
	void negativeInputsAreRejected() {
		assertThatThrownBy(() -> Gstr3bSetOff.setOff(h(-1, 0, 0), h(0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
	}
}
