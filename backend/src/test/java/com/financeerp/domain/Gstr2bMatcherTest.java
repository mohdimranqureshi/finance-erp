package com.financeerp.domain;

import com.financeerp.domain.Gstr2bMatcher.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class Gstr2bMatcherTest {
	private static final String V = "29AAPFU0939F1ZR";
	private static final LocalDate D = LocalDate.of(2026, 5, 10);

	private static Doc doc(long id, String number, LocalDate date, String taxable, String igst, String cgst, String sgst) {
		return new Doc(id, V, number, date, new BigDecimal(taxable), new BigDecimal(igst), new BigDecimal(cgst), new BigDecimal(sgst));
	}

	private static SupplierLine line(Doc d) {
		return new SupplierLine(d, true, false);
	}

	@Test
	void ignoresCasePunctuationAndRoundingWithinOneRupee() {
		List<Result> r = Gstr2bMatcher.match(List.of(line(doc(1, "inv/26-27/001", D, "10000.60", "1800.40", "0", "0"))),
				List.of(doc(10, "INV-26-27-001", D, "10000", "1800", "0", "0")), b -> true);
		assertThat(r).singleElement().satisfies(x -> {
			assertThat(x.status()).isEqualTo(Status.MATCHED);
			assertThat(x.bookId()).isEqualTo(10L);
		});
	}

	@Test
	void leadingZeroFallbackStillReportsValueAndDateDifferences() {
		List<Result> r = Gstr2bMatcher.match(List.of(line(doc(1, "A-7", D.plusDays(1), "9000", "1620", "0", "0"))),
				List.of(doc(10, "A-007", D, "10000", "1800", "0", "0")), b -> true);
		assertThat(r).singleElement().satisfies(x -> {
			assertThat(x.status()).isEqualTo(Status.MISMATCH);
			assertThat(x.reasons()).anyMatch(s -> s.startsWith("Number format differs"))
					.anyMatch(s -> s.startsWith("Taxable value differs"))
					.anyMatch(s -> s.startsWith("IGST differs"))
					.anyMatch(s -> s.startsWith("Date differs"));
		});
	}

	@Test
	void flagsTaxTypeMismatchFromWrongPlaceOfSupply() {
		List<Result> r = Gstr2bMatcher.match(List.of(line(doc(1, "X1", D, "10000", "0", "900", "900"))),
				List.of(doc(10, "X1", D, "10000", "1800", "0", "0")), b -> true);
		assertThat(r.get(0).status()).isEqualTo(Status.MISMATCH);
		assertThat(r.get(0).reasons()).anyMatch(s -> s.startsWith("Tax type differs"));
	}

	@Test
	void reportsBothSidesMissingWithCallerReasonsAndRespectsReportingFilter() {
		Doc reported = doc(10, "B-1", D, "100", "18", "0", "0");
		Doc notReported = doc(11, "B-2", D.minusYears(1), "100", "18", "0", "0");
		List<Result> r = Gstr2bMatcher.match(List.of(line(doc(1, "P-9", D, "100", "18", "0", "0"))),
				List.of(reported, notReported), b -> b.id() == 10L, "not in 2B", "not in books");
		assertThat(r).extracting(Result::status).containsExactly(Status.MISSING_IN_BOOKS, Status.MISSING_IN_2B);
		assertThat(r.get(0).reasons()).containsExactly("not in books");
		assertThat(r.get(1).bookId()).isEqualTo(10L);
		assertThat(r.get(1).reasons()).containsExactly("not in 2B");
	}

	@Test
	void unavailableItcOverridesMatchStatus() {
		Doc bill = doc(10, "R1", D, "100", "18", "0", "0");
		assertThat(Gstr2bMatcher.match(List.of(new SupplierLine(doc(1, "R1", D, "100", "18", "0", "0"), false, false)),
				List.of(bill), b -> true).get(0).status()).isEqualTo(Status.ITC_NOT_AVAILABLE);
	}

	@Test
	void reverseChargeMatchesOnlyAgainstReverseChargeBills() {
		SupplierLine rcmLine = new SupplierLine(doc(1, "R1", D, "100", "18", "0", "0"), true, true);
		Doc rcmBill = new Doc(10, V, "R1", D, new BigDecimal("100"), new BigDecimal("18"), BigDecimal.ZERO, BigDecimal.ZERO, true);
		assertThat(Gstr2bMatcher.match(List.of(rcmLine), List.of(rcmBill), b -> true).get(0).status())
				.isEqualTo(Status.MATCHED);
		Result normalBill = Gstr2bMatcher.match(List.of(rcmLine), List.of(doc(10, "R1", D, "100", "18", "0", "0")), b -> true).get(0);
		assertThat(normalBill.status()).isEqualTo(Status.MISMATCH);
		assertThat(normalBill.reasons()).anyMatch(s -> s.contains("not marked reverse charge"));
		Result unrecorded = Gstr2bMatcher.match(List.of(rcmLine), List.of(), b -> true).get(0);
		assertThat(unrecorded.status()).isEqualTo(Status.MISSING_IN_BOOKS);
		assertThat(unrecorded.reasons()).anyMatch(s -> s.contains("reverse-charge bill"));
	}

	@Test
	void eachBookDocumentMatchesAtMostOnePortalLine() {
		List<Result> r = Gstr2bMatcher.match(
				List.of(line(doc(1, "D1", D, "100", "18", "0", "0")), line(doc(2, "D-1", D, "100", "18", "0", "0"))),
				List.of(doc(10, "D1", D, "100", "18", "0", "0")), b -> true);
		assertThat(r).extracting(Result::status).containsExactly(Status.MATCHED, Status.MISSING_IN_BOOKS);
	}

	@Test
	void amendmentFallsBackToOriginalNumberAndIsFlagged() {
		Doc bill = doc(10, "OLD-1", D, "100", "18", "0", "0");
		List<Result> matched = Gstr2bMatcher.match(
				List.of(new SupplierLine(doc(1, "NEW-1", D, "100", "18", "0", "0"), true, false, "OLD-1")),
				List.of(bill), b -> true);
		assertThat(matched).singleElement().satisfies(x -> {
			assertThat(x.status()).isEqualTo(Status.MISMATCH);
			assertThat(x.reasons()).anyMatch(s -> s.contains("amended OLD-1 to NEW-1"));
		});
		List<Result> unmatched = Gstr2bMatcher.match(
				List.of(new SupplierLine(doc(1, "NEW-2", D, "100", "18", "0", "0"), true, false, "OLD-2")),
				List.of(), b -> true);
		assertThat(unmatched.get(0).status()).isEqualTo(Status.AMENDMENT_REVIEW);
	}
}
