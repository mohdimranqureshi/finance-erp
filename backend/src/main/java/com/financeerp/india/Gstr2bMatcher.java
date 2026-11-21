package com.financeerp.india;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

/** Pure GSTR-2B to books matching with no I/O, so it can be unit tested. */
public final class Gstr2bMatcher {
	/** Per-head tolerance for portal rounding. */
	public static final BigDecimal TOLERANCE = new BigDecimal("1.00");
	static final String MISSING_IN_2B_INVOICE = "Not in this GSTR-2B: ask the supplier to file GSTR-1/IFF before claiming ITC.";

	/** REVERSE_CHARGE is kept for rows created before RCM bills were supported; new runs no longer produce it. */
	public enum Status { MATCHED, MISMATCH, MISSING_IN_BOOKS, MISSING_IN_2B, ITC_NOT_AVAILABLE, REVERSE_CHARGE, AMENDMENT_REVIEW }

	public record Doc(long id, String gstin, String invoiceNumber, LocalDate date, BigDecimal taxable,
			BigDecimal igst, BigDecimal cgst, BigDecimal sgst, boolean reverseCharge) {
		public Doc(long id, String gstin, String invoiceNumber, LocalDate date, BigDecimal taxable, BigDecimal igst,
				BigDecimal cgst, BigDecimal sgst) {
			this(id, gstin, invoiceNumber, date, taxable, igst, cgst, sgst, false);
		}
	}

	/** originalNumber is set only for amendments (B2BA). */
	public record SupplierLine(Doc doc, boolean itcAvailable, boolean reverseCharge, String originalNumber) {
		public SupplierLine(Doc doc, boolean itcAvailable, boolean reverseCharge) {
			this(doc, itcAvailable, reverseCharge, null);
		}
	}

	/** bookId is the bill or note id, depending on which book documents were passed in. */
	public record Result(Long lineId, Long bookId, Status status, List<String> reasons) {
	}

	private Gstr2bMatcher() {
	}

	static String key(String number) {
		return number.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
	}

	/** Also drops leading zeros of each numeric run, so INV/001 matches INV-1. */
	static String looseKey(String number) {
		return key(number).replaceAll("(?<![0-9])0+(?=[0-9])", "");
	}

	public static List<Result> match(List<SupplierLine> lines, List<Doc> books, Predicate<Doc> reportIfMissing) {
		return match(lines, books, reportIfMissing, MISSING_IN_2B_INVOICE, null);
	}

	public static List<Result> match(List<SupplierLine> lines, List<Doc> books, Predicate<Doc> reportIfMissing,
			String missingIn2bReason, String missingInBooksReason) {
		Map<String, List<Doc>> exact = new HashMap<>();
		Map<String, List<Doc>> loose = new HashMap<>();
		for (Doc b : books) {
			exact.computeIfAbsent(b.gstin() + "|" + key(b.invoiceNumber()), k -> new ArrayList<>()).add(b);
			loose.computeIfAbsent(b.gstin() + "|" + looseKey(b.invoiceNumber()), k -> new ArrayList<>()).add(b);
		}
		Set<Long> used = new HashSet<>();
		List<Result> results = new ArrayList<>();
		for (SupplierLine line : lines) {
			Doc d = line.doc();
			List<String> reasons = new ArrayList<>();
			boolean viaOriginal = false;
			Doc book = find(exact, loose, d.gstin(), d.invoiceNumber(), used, reasons);
			if (book == null && line.originalNumber() != null) {
				book = find(exact, loose, d.gstin(), line.originalNumber(), used, reasons);
				if (book != null) {
					reasons.add("Supplier amended " + line.originalNumber() + " to " + d.invoiceNumber()
							+ ": update the bill to the amended details.");
					viaOriginal = true;
				}
			}
			if (!line.itcAvailable())
				reasons.add("GSTR-2B marks this ITC as not available.");
			if (book == null) {
				Status s = line.originalNumber() != null ? Status.AMENDMENT_REVIEW : Status.MISSING_IN_BOOKS;
				if (line.reverseCharge())
					reasons.add("Reverse-charge supply: record it as a reverse-charge bill and pay the tax in cash.");
				if (s == Status.AMENDMENT_REVIEW)
					reasons.add("Amendment of " + line.originalNumber() + ": if the original was matched in an earlier return, "
							+ "update that bill to the amended values; otherwise record it.");
				else if (missingInBooksReason != null)
					reasons.add(missingInBooksReason);
				results.add(new Result(d.id(), null, s, reasons));
				continue;
			}
			boolean mismatch = viaOriginal;
			if (line.reverseCharge() != book.reverseCharge()) {
				reasons.add(line.reverseCharge()
						? "Supplier reported this under reverse charge, but the bill is not marked reverse charge."
						: "The bill is marked reverse charge, but the supplier charged GST on the invoice.");
				mismatch = true;
			}
			mismatch |= differs(reasons, "Taxable value", book.taxable(), d.taxable());
			boolean booksIgst = book.igst().signum() != 0, portalIgst = d.igst().signum() != 0;
			if (booksIgst != portalIgst) {
				reasons.add("Tax type differs: books " + (booksIgst ? "IGST" : "CGST+SGST") + ", 2B "
						+ (portalIgst ? "IGST" : "CGST+SGST") + " (check place of supply).");
				mismatch = true;
			} else {
				mismatch |= differs(reasons, "IGST", book.igst(), d.igst());
				mismatch |= differs(reasons, "CGST", book.cgst(), d.cgst());
				mismatch |= differs(reasons, "SGST", book.sgst(), d.sgst());
			}
			if (!book.date().equals(d.date())) {
				reasons.add("Date differs: books " + book.date() + ", 2B " + d.date());
				mismatch = true;
			}
			Status status = !line.itcAvailable() ? Status.ITC_NOT_AVAILABLE
					: mismatch ? Status.MISMATCH : Status.MATCHED;
			results.add(new Result(d.id(), book.id(), status, reasons));
		}
		for (Doc b : books)
			if (!used.contains(b.id()) && reportIfMissing.test(b))
				results.add(new Result(null, b.id(), Status.MISSING_IN_2B, List.of(missingIn2bReason)));
		return results;
	}

	private static Doc find(Map<String, List<Doc>> exact, Map<String, List<Doc>> loose, String gstin, String number,
			Set<Long> used, List<String> reasons) {
		Doc doc = take(exact.get(gstin + "|" + key(number)), used);
		if (doc != null)
			return doc;
		doc = take(loose.get(gstin + "|" + looseKey(number)), used);
		if (doc != null)
			reasons.add("Number format differs: books " + doc.invoiceNumber() + ", 2B " + number);
		return doc;
	}

	private static Doc take(List<Doc> candidates, Set<Long> used) {
		if (candidates != null)
			for (Doc c : candidates)
				if (used.add(c.id()))
					return c;
		return null;
	}

	private static boolean differs(List<String> reasons, String label, BigDecimal books, BigDecimal portal) {
		if (books.subtract(portal).abs().compareTo(TOLERANCE) <= 0)
			return false;
		reasons.add(label + " differs: books " + plain(books) + ", 2B " + plain(portal));
		return true;
	}

	private static String plain(BigDecimal v) {
		return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}
}
