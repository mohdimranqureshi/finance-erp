package com.financeerp.india;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure validation and journal construction for an opening-balance batch (no database access). */
public final class OpeningBalancePlanner {
	public static final int MAX_LINES = 500;
	public static final int MAX_ITEMS = 2000;
	static final String DEFAULT_MEMO = "Opening balance";

	private OpeningBalancePlanner() {
	}

	public record GlLine(Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
	}

	public record PartyItem(Long partyId, String documentNumber, LocalDate documentDate, LocalDate dueDate,
			BigDecimal amount) {
	}

	/** System account ids the batch posts through. */
	public record Accounts(long ar, long ap, long openingBalance) {
	}

	/** equityAdjustment is signed: positive credits OPENING_BALANCE, negative debits it. */
	public record Plan(List<LedgerPoster.Line> lines, List<PartyItem> receivables, List<PartyItem> payables,
			BigDecimal receivableTotal, BigDecimal payableTotal, BigDecimal equityAdjustment) {
	}

	public static Plan plan(LocalDate asOfDate, List<GlLine> glLines, List<PartyItem> receivables,
			List<PartyItem> payables, Accounts accounts) {
		if (asOfDate == null)
			throw new IllegalArgumentException("asOfDate is required.");
		List<GlLine> gl = glLines == null ? List.of() : glLines;
		List<PartyItem> rec = receivables == null ? List.of() : receivables;
		List<PartyItem> pay = payables == null ? List.of() : payables;
		if (gl.isEmpty() && rec.isEmpty() && pay.isEmpty())
			throw new IllegalArgumentException("Enter at least one opening balance line, receivable or payable.");
		if (gl.size() > MAX_LINES)
			throw new IllegalArgumentException("At most " + MAX_LINES + " opening balance lines are allowed.");
		if (rec.size() > MAX_ITEMS || pay.size() > MAX_ITEMS)
			throw new IllegalArgumentException("At most " + MAX_ITEMS + " receivables and " + MAX_ITEMS + " payables are allowed.");

		List<LedgerPoster.Line> lines = new ArrayList<>();
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		for (int i = 0; i < gl.size(); i++) {
			GlLine l = gl.get(i);
			String at = "Line " + (i + 1) + ": ";
			if (l == null || l.accountId() == null)
				throw new IllegalArgumentException(at + "accountId is required.");
			if (l.accountId() == accounts.ar() || l.accountId() == accounts.ap())
				throw new IllegalArgumentException(at + "Trade Receivables/Payables cannot be entered directly; "
						+ "enter customer receivables and vendor payables instead so the party ledger agrees with the GL.");
			BigDecimal debit = l.debit() == null ? BigDecimal.ZERO : l.debit();
			BigDecimal credit = l.credit() == null ? BigDecimal.ZERO : l.credit();
			checkAmount(debit, at + "Debit");
			checkAmount(credit, at + "Credit");
			if ((debit.signum() > 0) == (credit.signum() > 0))
				throw new IllegalArgumentException(at + "enter exactly one positive debit or credit.");
			String memo = l.memo() == null || l.memo().isBlank() ? DEFAULT_MEMO : l.memo().trim();
			if (memo.length() > 500)
				throw new IllegalArgumentException(at + "memo must be 500 characters or fewer.");
			lines.add(new LedgerPoster.Line(l.accountId(), memo, debit, credit));
			debits = debits.add(debit);
			credits = credits.add(credit);
		}
		List<PartyItem> recOut = items(asOfDate, rec, "Receivable");
		List<PartyItem> payOut = items(asOfDate, pay, "Payable");
		BigDecimal recTotal = total(recOut), payTotal = total(payOut);
		if (recTotal.signum() > 0) {
			lines.add(new LedgerPoster.Line(accounts.ar(), "Opening customer receivables", recTotal, BigDecimal.ZERO));
			debits = debits.add(recTotal);
		}
		if (payTotal.signum() > 0) {
			lines.add(new LedgerPoster.Line(accounts.ap(), "Opening vendor payables", BigDecimal.ZERO, payTotal));
			credits = credits.add(payTotal);
		}
		BigDecimal adjustment = debits.subtract(credits);
		if (adjustment.signum() > 0)
			lines.add(new LedgerPoster.Line(accounts.openingBalance(), "Opening balance adjustment", BigDecimal.ZERO, adjustment));
		else if (adjustment.signum() < 0)
			lines.add(new LedgerPoster.Line(accounts.openingBalance(), "Opening balance adjustment", adjustment.negate(),
					BigDecimal.ZERO));
		return new Plan(List.copyOf(lines), recOut, payOut, recTotal, payTotal, adjustment);
	}

	private static List<PartyItem> items(LocalDate asOfDate, List<PartyItem> in, String label) {
		List<PartyItem> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < in.size(); i++) {
			PartyItem p = in.get(i);
			String at = label + " " + (i + 1) + ": ";
			if (p == null || p.partyId() == null)
				throw new IllegalArgumentException(at + "party is required.");
			String number = p.documentNumber() == null ? "" : p.documentNumber().trim();
			if (number.isEmpty() || number.length() > 50)
				throw new IllegalArgumentException(at + "documentNumber is required (at most 50 characters).");
			if (p.documentDate() == null)
				throw new IllegalArgumentException(at + "documentDate is required.");
			if (p.documentDate().isAfter(asOfDate))
				throw new IllegalArgumentException(at + "documentDate " + p.documentDate()
						+ " is after the opening balance date " + asOfDate + ".");
			if (p.dueDate() != null && p.dueDate().isBefore(p.documentDate()))
				throw new IllegalArgumentException(at + "dueDate cannot be before documentDate.");
			if (p.amount() == null)
				throw new IllegalArgumentException(at + "amount is required.");
			checkAmount(p.amount(), at + "Amount");
			if (p.amount().signum() <= 0)
				throw new IllegalArgumentException(at + "amount must be greater than zero.");
			if (!seen.add(p.partyId() + "\u0000" + number.toLowerCase(Locale.ROOT)))
				throw new IllegalArgumentException(at + "document " + number + " is repeated for the same party.");
			out.add(new PartyItem(p.partyId(), number, p.documentDate(), p.dueDate(), p.amount()));
		}
		return List.copyOf(out);
	}

	private static BigDecimal total(List<PartyItem> items) {
		return items.stream().map(PartyItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** Non-negative, at most four decimals and 13 integer digits, leaving headroom for totals in NUMERIC(19,4). */
	static void checkAmount(BigDecimal value, String label) {
		BigDecimal v = value.stripTrailingZeros();
		int integerDigits = Math.max(0, v.precision() - v.scale());
		if (value.signum() < 0 || v.scale() > 4 || integerDigits > 13)
			throw new IllegalArgumentException(
					label + " must be non-negative, have at most four decimal places and at most 13 integer digits.");
	}
}
