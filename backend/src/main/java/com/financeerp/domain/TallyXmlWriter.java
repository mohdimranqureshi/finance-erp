package com.financeerp.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Builds a Tally "Import Data" envelope (masters + vouchers) that TallyPrime can import via Import > Transactions. */
public final class TallyXmlWriter {
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private TallyXmlWriter() {
	}

	public record LedgerOut(String name, String parent) {
	}

	public record LineOut(String ledgerName, BigDecimal debit, BigDecimal credit) {
	}

	public record VoucherOut(String number, LocalDate date, String narration, List<LineOut> lines) {
	}

	public record AccountRef(long id, String code, String name) {
	}

	/**
	 * Tally ledger names must be unique and may not reuse a predefined group name, so duplicates and clashes get the
	 * account code appended: "Bank Accounts" becomes "Bank Accounts (1010)".
	 */
	public static Map<Long, String> ledgerNames(Collection<AccountRef> accounts) {
		Map<String, Integer> counts = new HashMap<>();
		for (AccountRef a : accounts)
			counts.merge(a.name().trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
		Map<Long, String> out = new LinkedHashMap<>();
		Set<String> used = new HashSet<>();
		for (AccountRef a : accounts) {
			String name = a.name().trim();
			String key = name.toLowerCase(Locale.ROOT);
			if (counts.get(key) > 1 || TallyGroups.isPredefined(name) || key.equals("primary"))
				name = name + " (" + a.code() + ")";
			while (!used.add(name.toLowerCase(Locale.ROOT)))
				name = name + " #" + a.id();
			out.put(a.id(), name);
		}
		return out;
	}

	public static String write(List<LedgerOut> ledgers, List<VoucherOut> vouchers) {
		StringBuilder x = new StringBuilder(4096 + vouchers.size() * 600);
		x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		x.append("<ENVELOPE>\n");
		x.append(" <HEADER>\n  <TALLYREQUEST>Import Data</TALLYREQUEST>\n </HEADER>\n");
		x.append(" <BODY>\n  <IMPORTDATA>\n");
		x.append("   <REQUESTDESC>\n    <REPORTNAME>Vouchers</REPORTNAME>\n   </REQUESTDESC>\n");
		x.append("   <REQUESTDATA>\n");
		for (LedgerOut l : ledgers) {
			x.append("    <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
			x.append("     <LEDGER NAME=\"").append(escape(l.name())).append("\" ACTION=\"Create\">\n");
			x.append("      <NAME.LIST>\n       <NAME>").append(escape(l.name())).append("</NAME>\n      </NAME.LIST>\n");
			x.append("      <PARENT>").append(escape(l.parent())).append("</PARENT>\n");
			x.append("      <ISBILLWISEON>No</ISBILLWISEON>\n      <AFFECTSSTOCK>No</AFFECTSSTOCK>\n");
			x.append("     </LEDGER>\n    </TALLYMESSAGE>\n");
		}
		for (VoucherOut v : vouchers) {
			x.append("    <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n");
			x.append("     <VOUCHER VCHTYPE=\"Journal\" ACTION=\"Create\" OBJVIEW=\"Accounting Voucher View\">\n");
			x.append("      <DATE>").append(v.date().format(DATE)).append("</DATE>\n");
			x.append("      <VOUCHERTYPENAME>Journal</VOUCHERTYPENAME>\n");
			x.append("      <VOUCHERNUMBER>").append(escape(v.number())).append("</VOUCHERNUMBER>\n");
			if (v.narration() != null && !v.narration().isBlank())
				x.append("      <NARRATION>").append(escape(v.narration())).append("</NARRATION>\n");
			x.append("      <PERSISTEDVIEW>Accounting Voucher View</PERSISTEDVIEW>\n");
			for (LineOut l : v.lines()) {
				boolean debit = l.debit() != null && l.debit().signum() > 0;
				BigDecimal value = debit ? l.debit() : l.credit();
				x.append("      <ALLLEDGERENTRIES.LIST>\n");
				x.append("       <LEDGERNAME>").append(escape(l.ledgerName())).append("</LEDGERNAME>\n");
				x.append("       <ISDEEMEDPOSITIVE>").append(debit ? "Yes" : "No").append("</ISDEEMEDPOSITIVE>\n");
				x.append("       <AMOUNT>").append(amount(debit ? value.negate() : value)).append("</AMOUNT>\n");
				x.append("      </ALLLEDGERENTRIES.LIST>\n");
			}
			x.append("     </VOUCHER>\n    </TALLYMESSAGE>\n");
		}
		x.append("   </REQUESTDATA>\n  </IMPORTDATA>\n </BODY>\n</ENVELOPE>\n");
		return x.toString();
	}

	/** Tally sign: debit negative, credit positive; at least two decimals. */
	static String amount(BigDecimal tallySigned) {
		BigDecimal v = tallySigned.stripTrailingZeros();
		if (v.scale() < 2)
			v = v.setScale(2);
		return v.toPlainString();
	}

	/** Escapes markup characters and drops characters that XML 1.0 cannot carry. */
	static String escape(String s) {
		if (s == null)
			return "";
		StringBuilder b = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '&' -> b.append("&amp;");
			case '<' -> b.append("&lt;");
			case '>' -> b.append("&gt;");
			case '"' -> b.append("&quot;");
			case '\'' -> b.append("&apos;");
			default -> {
				if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF)
					continue;
				b.append(c);
			}
			}
		}
		return b.toString();
	}
}
