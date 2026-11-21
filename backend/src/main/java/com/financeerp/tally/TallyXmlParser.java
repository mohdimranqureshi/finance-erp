package com.financeerp.tally;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming, DTD-free reader for Tally XML exports (ENVELOPE/BODY/.../TALLYMESSAGE). Only GROUP, LEDGER, VOUCHERTYPE
 * and VOUCHER objects are materialised; everything else (stock items, units, currencies, UDFs) is skipped.
 *
 * <p>Sign convention: in Tally XML a negative AMOUNT is a debit and a positive AMOUNT is a credit, so the amounts of a
 * balanced voucher sum to zero. ISDEEMEDPOSITIVE=Yes normally accompanies the negative (debit) side; the AMOUNT sign is
 * authoritative and a disagreeing ISDEEMEDPOSITIVE is only reported as a warning. OPENINGBALANCE uses the same
 * convention (negative = Dr). Amounts written as "1000.00 Dr"/"1000.00 Cr" are accepted.
 */
public final class TallyXmlParser {
	public static final long MAX_BYTES = 10L * 1024 * 1024;
	static final int MAX_GROUPS = 10_000;
	static final int MAX_LEDGERS = 50_000;
	static final int MAX_VOUCHER_TYPES = 2_000;
	static final int MAX_VOUCHERS = 50_000;
	static final int MAX_ENTRIES_PER_VOUCHER = 2_000;
	static final int MAX_DEPTH = 64;
	static final int MAX_TEXT = 100_000;
	static final int MAX_WARNINGS = 200;
	private static final Set<String> CAPTURED = Set.of("GROUP", "LEDGER", "VOUCHERTYPE", "VOUCHER");
	private static final Pattern CHAR_REF = Pattern.compile("&#(x[0-9a-fA-F]{1,8}|[0-9]{1,10});");
	private static final Pattern FORBIDDEN_DECL = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");
	private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("uuuuMMdd")
			.withResolverStyle(ResolverStyle.STRICT);
	private static final List<DateTimeFormatter> OTHER_DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
			new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH),
			new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uu").toFormatter(Locale.ENGLISH));

	private TallyXmlParser() {
	}

	public record Group(String name, String parent, String reservedName, Boolean revenue, Boolean deemedPositive) {
	}

	/** openingBalance follows the Tally sign (negative = Dr); null when absent. */
	public record Ledger(String name, String parent, BigDecimal openingBalance, String gstin, String stateName) {
	}

	public record VoucherType(String name, String parent) {
	}

	/** amount follows the Tally sign: negative = debit, positive = credit; null when unreadable. */
	public record Entry(String ledgerName, BigDecimal amount, Boolean deemedPositive) {
		public boolean debit() {
			return amount.signum() < 0;
		}
	}

	/** parseError is set when the date or an amount could not be read; such vouchers are never imported. */
	public record Voucher(String voucherType, String rawDate, LocalDate date, String number, String guid,
			String narration, boolean cancelled, boolean optional, boolean deleted, List<Entry> entries,
			String parseError) {
		public BigDecimal total() {
			BigDecimal sum = BigDecimal.ZERO;
			for (Entry e : entries)
				if (e.amount() != null)
					sum = sum.add(e.amount());
			return sum;
		}

		public BigDecimal debits() {
			BigDecimal sum = BigDecimal.ZERO;
			for (Entry e : entries)
				if (e.amount() != null && e.amount().signum() < 0)
					sum = sum.add(e.amount().negate());
			return sum;
		}

		public BigDecimal credits() {
			BigDecimal sum = BigDecimal.ZERO;
			for (Entry e : entries)
				if (e.amount() != null && e.amount().signum() > 0)
					sum = sum.add(e.amount());
			return sum;
		}
	}

	public record TallyData(List<Group> groups, List<Ledger> ledgers, List<VoucherType> voucherTypes,
			List<Voucher> vouchers, List<String> warnings) {
	}

	public static TallyData parse(byte[] bytes) {
		if (bytes == null || bytes.length == 0)
			throw new IllegalArgumentException("The file is empty.");
		if (bytes.length > MAX_BYTES)
			throw new IllegalArgumentException("The Tally XML file is larger than 10 MB.");
		return parse(decode(bytes));
	}

	public static TallyData parse(String xml) {
		String text = sanitize(xml);
		if (FORBIDDEN_DECL.matcher(text).find())
			throw new IllegalArgumentException("DTDs and entity declarations are not allowed in Tally XML.");
		Builder out = new Builder();
		XMLStreamReader r = null;
		try {
			r = factory().createXMLStreamReader(new StringReader(text));
			Deque<Node> stack = new ArrayDeque<>();
			int depth = 0;
			boolean sawRoot = false;
			while (r.hasNext()) {
				int event = r.next();
				switch (event) {
				case XMLStreamConstants.DTD ->
					throw new IllegalArgumentException("DTDs are not allowed in Tally XML.");
				case XMLStreamConstants.ENTITY_REFERENCE ->
					throw new IllegalArgumentException("Entity references are not allowed in Tally XML.");
				case XMLStreamConstants.START_ELEMENT -> {
					if (++depth > MAX_DEPTH)
						throw new IllegalArgumentException("The XML is nested too deeply.");
					String name = r.getLocalName().toUpperCase(Locale.ROOT);
					if (!sawRoot) {
						sawRoot = true;
						if (!"ENVELOPE".equals(name))
							throw new IllegalArgumentException("Not a Tally XML export: the root element must be ENVELOPE.");
					}
					if (!stack.isEmpty() || CAPTURED.contains(name)) {
						Node node = new Node(name);
						for (int i = 0; i < r.getAttributeCount(); i++)
							node.attrs.put(r.getAttributeLocalName(i).toUpperCase(Locale.ROOT), r.getAttributeValue(i));
						if (!stack.isEmpty())
							stack.peek().children.add(node);
						stack.push(node);
					}
				}
				case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
					if (!stack.isEmpty())
						stack.peek().append(r.getText());
				}
				case XMLStreamConstants.END_ELEMENT -> {
					depth--;
					if (!stack.isEmpty()) {
						Node done = stack.pop();
						if (stack.isEmpty())
							out.accept(done);
					}
				}
				default -> {
				}
				}
			}
			if (!sawRoot)
				throw new IllegalArgumentException("Not a Tally XML export: no XML elements found.");
		} catch (XMLStreamException e) {
			String where = e.getLocation() == null ? "" : " near line " + e.getLocation().getLineNumber();
			throw new IllegalArgumentException("The file is not well-formed XML" + where + ".");
		} finally {
			if (r != null)
				try {
					r.close();
				} catch (XMLStreamException ignored) {
				}
		}
		return new TallyData(List.copyOf(out.groups), List.copyOf(out.ledgers), List.copyOf(out.voucherTypes),
				List.copyOf(out.vouchers), List.copyOf(out.warnings));
	}

	private static XMLInputFactory factory() {
		XMLInputFactory f = XMLInputFactory.newFactory();
		f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
		f.setProperty(XMLInputFactory.IS_COALESCING, true);
		trySet(f, XMLConstants.ACCESS_EXTERNAL_DTD, "");
		trySet(f, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		return f;
	}

	private static void trySet(XMLInputFactory f, String property, Object value) {
		try {
			f.setProperty(property, value);
		} catch (IllegalArgumentException ignored) {
			// Not every StAX implementation knows these JAXP properties; DTDs are refused before parsing anyway.
		}
	}

	/** Tally writes UTF-16LE with a BOM by default; UTF-8 (with or without BOM) is also accepted. */
	static String decode(byte[] b) {
		String s;
		if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
			s = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
		else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
			s = new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
		else if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
			s = strict(Arrays.copyOfRange(b, 3, b.length), StandardCharsets.UTF_8);
		else if (b.length >= 2 && b[0] != 0 && b[1] == 0)
			s = new String(b, StandardCharsets.UTF_16LE);
		else if (b.length >= 2 && b[0] == 0 && b[1] != 0)
			s = new String(b, StandardCharsets.UTF_16BE);
		else
			s = strict(b, StandardCharsets.UTF_8);
		return s.startsWith("\uFEFF") ? s.substring(1) : s;
	}

	private static String strict(byte[] b, Charset cs) {
		try {
			return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
		} catch (CharacterCodingException e) {
			// Older Tally "ASCII" exports are really Windows-1252.
			return new String(b, Charset.forName("windows-1252"));
		}
	}

	/**
	 * Tally emits character references that XML 1.0 forbids (notably "&#4; Primary" for top-level groups) and
	 * occasionally raw control characters. Both are dropped so the rest of the file can be read.
	 */
	static String sanitize(String s) {
		Matcher m = CHAR_REF.matcher(s);
		StringBuilder refs = new StringBuilder(s.length());
		while (m.find()) {
			String v = m.group(1);
			long cp;
			try {
				cp = v.startsWith("x") ? Long.parseLong(v.substring(1), 16) : Long.parseLong(v);
			} catch (NumberFormatException e) {
				cp = -1;
			}
			m.appendReplacement(refs, validXmlChar(cp) ? Matcher.quoteReplacement(m.group()) : "");
		}
		m.appendTail(refs);
		StringBuilder clean = new StringBuilder(refs.length());
		for (int i = 0; i < refs.length(); i++) {
			char c = refs.charAt(i);
			if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF)
				continue;
			clean.append(c);
		}
		return clean.toString();
	}

	private static boolean validXmlChar(long cp) {
		return cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD)
				|| (cp >= 0x10000 && cp <= 0x10FFFF);
	}

	/**
	 * Parses Tally amount text. Returns null for blank input and throws IllegalArgumentException for text that is not
	 * an amount. Accepts "-1,00,000.00", "1000.00 Dr", "1000.00 Cr", "₹ 500", "(250.00)" and forex forms such as
	 * "-$100.00 @ ₹ 80.00/$ = -₹ 8000.00" (the base-currency value after the last '=' is used).
	 */
	public static BigDecimal parseAmount(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		String s = raw.trim();
		int eq = s.lastIndexOf('=');
		if (eq >= 0)
			s = s.substring(eq + 1).trim();
		else if (s.indexOf('@') >= 0)
			throw new IllegalArgumentException("Foreign-currency amount without a base-currency value: " + raw);
		int side = 0;
		String lower = s.toLowerCase(Locale.ROOT);
		if (lower.endsWith("dr")) {
			side = -1;
			s = s.substring(0, s.length() - 2);
		} else if (lower.endsWith("cr")) {
			side = 1;
			s = s.substring(0, s.length() - 2);
		}
		boolean parenthesised = s.contains("(") && s.contains(")");
		s = s.replaceAll("(?i)rs\\.?|inr", "").replaceAll("[^0-9.\\-]", "");
		if (s.isEmpty() || s.equals("-") || s.equals("."))
			throw new IllegalArgumentException("Not an amount: " + raw);
		BigDecimal v;
		try {
			v = new BigDecimal(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not an amount: " + raw);
		}
		if (side != 0)
			v = side < 0 ? v.abs().negate() : v.abs();
		else if (parenthesised)
			v = v.abs().negate();
		if (v.scale() > 4)
			v = v.setScale(4, RoundingMode.HALF_UP);
		if (v.abs().compareTo(MAX_AMOUNT) >= 0)
			throw new IllegalArgumentException("Amount is too large: " + raw);
		return v;
	}

	public static LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		String s = raw.trim();
		try {
			return LocalDate.parse(s, BASIC);
		} catch (DateTimeParseException ignored) {
		}
		for (DateTimeFormatter f : OTHER_DATES)
			try {
				return LocalDate.parse(s, f);
			} catch (DateTimeParseException ignored) {
			}
		return null;
	}

	static Boolean yesNo(String v) {
		if (v == null || v.isBlank())
			return null;
		return switch (v.trim().toLowerCase(Locale.ROOT)) {
		case "yes", "true", "1" -> Boolean.TRUE;
		case "no", "false", "0" -> Boolean.FALSE;
		default -> null;
		};
	}

	static String cleanName(String s) {
		if (s == null)
			return null;
		String t = s.replaceAll("\\s+", " ").trim();
		return t.isEmpty() ? null : t;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values)
			if (v != null && !v.isBlank())
				return v.trim();
		return null;
	}

	/** Minimal element tree for one captured Tally object. */
	static final class Node {
		final String name;
		final Map<String, String> attrs = new HashMap<>();
		final List<Node> children = new ArrayList<>();
		private StringBuilder text;

		Node(String name) {
			this.name = name;
		}

		void append(String s) {
			if (text == null)
				text = new StringBuilder();
			if (text.length() + s.length() > MAX_TEXT)
				throw new IllegalArgumentException("An XML text value is longer than " + MAX_TEXT + " characters.");
			text.append(s);
		}

		String text() {
			return text == null ? "" : text.toString().trim();
		}

		String attr(String name) {
			String v = attrs.get(name);
			return v == null || v.isBlank() ? null : v.trim();
		}

		List<Node> children(String name) {
			List<Node> out = new ArrayList<>();
			for (Node c : children)
				if (c.name.equals(name))
					out.add(c);
			return out;
		}

		String childText(String name) {
			for (Node c : children)
				if (c.name.equals(name)) {
					String t = c.text();
					if (!t.isEmpty())
						return t;
				}
			return null;
		}

		/** First non-blank text of a descendant with the given name, depth-first. */
		String descendantText(String name) {
			for (Node c : children) {
				if (c.name.equals(name) && !c.text().isEmpty())
					return c.text();
				String deeper = c.descendantText(name);
				if (deeper != null)
					return deeper;
			}
			return null;
		}

		Node descendant(String name) {
			for (Node c : children) {
				if (c.name.equals(name))
					return c;
				Node deeper = c.descendant(name);
				if (deeper != null)
					return deeper;
			}
			return null;
		}
	}

	private static final class Builder {
		final List<Group> groups = new ArrayList<>();
		final List<Ledger> ledgers = new ArrayList<>();
		final List<VoucherType> voucherTypes = new ArrayList<>();
		final List<Voucher> vouchers = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();

		void warn(String w) {
			if (warnings.size() < MAX_WARNINGS)
				warnings.add(w);
			else if (warnings.size() == MAX_WARNINGS)
				warnings.add("Further parser warnings were suppressed.");
		}

		void accept(Node n) {
			switch (n.name) {
			case "GROUP" -> group(n);
			case "LEDGER" -> ledger(n);
			case "VOUCHERTYPE" -> voucherType(n);
			case "VOUCHER" -> voucher(n);
			default -> {
			}
			}
		}

		private static String masterName(Node n) {
			String name = n.attr("NAME");
			if (name == null) {
				Node list = n.descendant("NAME.LIST");
				name = list == null ? null : list.childText("NAME");
			}
			if (name == null)
				name = n.childText("NAME");
			return cleanName(name);
		}

		private void group(Node n) {
			String name = masterName(n);
			if (name == null) {
				warn("A GROUP without a name was ignored.");
				return;
			}
			if (groups.size() >= MAX_GROUPS)
				throw new IllegalArgumentException("The file has more than " + MAX_GROUPS + " groups.");
			groups.add(new Group(name, cleanName(n.childText("PARENT")), n.attr("RESERVEDNAME"),
					yesNo(n.childText("ISREVENUE")), yesNo(n.childText("ISDEEMEDPOSITIVE"))));
		}

		private void ledger(Node n) {
			String name = masterName(n);
			if (name == null) {
				warn("A LEDGER without a name was ignored.");
				return;
			}
			if (ledgers.size() >= MAX_LEDGERS)
				throw new IllegalArgumentException("The file has more than " + MAX_LEDGERS + " ledgers.");
			BigDecimal opening = null;
			try {
				opening = parseAmount(n.childText("OPENINGBALANCE"));
			} catch (IllegalArgumentException e) {
				warn("Ledger '" + name + "': opening balance could not be read.");
			}
			String gstin = firstNonBlank(n.descendantText("PARTYGSTIN"), n.descendantText("GSTIN"));
			String state = firstNonBlank(n.descendantText("LEDSTATENAME"), n.descendantText("STATENAME"),
					n.descendantText("STATE"));
			ledgers.add(new Ledger(name, cleanName(n.childText("PARENT")), opening, gstin, state));
		}

		private void voucherType(Node n) {
			String name = masterName(n);
			if (name == null)
				return;
			if (voucherTypes.size() >= MAX_VOUCHER_TYPES)
				throw new IllegalArgumentException("The file has more than " + MAX_VOUCHER_TYPES + " voucher types.");
			voucherTypes.add(new VoucherType(name, cleanName(n.childText("PARENT"))));
		}

		private void voucher(Node n) {
			if (vouchers.size() >= MAX_VOUCHERS)
				throw new IllegalArgumentException("The file has more than " + MAX_VOUCHERS + " vouchers.");
			String type = cleanName(firstNonBlank(n.childText("VOUCHERTYPENAME"), n.attr("VCHTYPE")));
			if (type == null)
				type = "Unknown";
			String rawDate = n.childText("DATE");
			LocalDate date = parseDate(rawDate);
			String number = n.childText("VOUCHERNUMBER");
			String guid = firstNonBlank(n.childText("GUID"), n.attr("REMOTEID"));
			List<String> errors = new ArrayList<>();
			if (date == null)
				errors.add(rawDate == null ? "voucher has no DATE" : "unreadable DATE '" + rawDate + "'");
			// ALLLEDGERENTRIES.LIST (accounting view) and LEDGERENTRIES.LIST (invoice view) are alternative renderings;
			// using both would double-count, so the ALL* variant wins when it carries ledger lines. Invoice-mode
			// vouchers carry the sales/purchase ledger inside inventory entries' ACCOUNTINGALLOCATIONS.LIST.
			List<Node> lines = new ArrayList<>(named(n.children("ALLLEDGERENTRIES.LIST")));
			if (lines.isEmpty())
				lines.addAll(named(n.children("LEDGERENTRIES.LIST")));
			List<Node> allocations = allocations(n.children("ALLINVENTORYENTRIES.LIST"));
			if (allocations.isEmpty())
				allocations = allocations(n.children("INVENTORYENTRIES.LIST"));
			lines.addAll(allocations);
			if (lines.size() > MAX_ENTRIES_PER_VOUCHER)
				throw new IllegalArgumentException(
						"A voucher has more than " + MAX_ENTRIES_PER_VOUCHER + " ledger entries.");
			List<Entry> entries = new ArrayList<>();
			int mismatched = 0;
			for (Node e : lines) {
				String ledger = cleanName(e.childText("LEDGERNAME"));
				Boolean deemed = yesNo(e.childText("ISDEEMEDPOSITIVE"));
				BigDecimal amount = null;
				try {
					amount = parseAmount(e.childText("AMOUNT"));
					if (amount == null)
						amount = BigDecimal.ZERO;
				} catch (IllegalArgumentException ex) {
					errors.add("unreadable AMOUNT for ledger '" + ledger + "'");
				}
				if (amount != null && deemed != null && amount.signum() != 0 && deemed != (amount.signum() < 0))
					mismatched++;
				entries.add(new Entry(ledger, amount, deemed));
			}
			if (mismatched > 0)
				warn("Voucher " + type + " " + (number == null ? "(no number)" : number) + " dated "
						+ (rawDate == null ? "?" : rawDate) + ": ISDEEMEDPOSITIVE disagrees with the AMOUNT sign on "
						+ mismatched + " line(s); the AMOUNT sign was used.");
			vouchers.add(new Voucher(type, rawDate, date, number, guid, n.childText("NARRATION"),
					Boolean.TRUE.equals(yesNo(n.childText("ISCANCELLED"))),
					Boolean.TRUE.equals(yesNo(n.childText("ISOPTIONAL"))),
					"Delete".equalsIgnoreCase(n.attr("ACTION")) || Boolean.TRUE.equals(yesNo(n.childText("ISDELETED"))),
					List.copyOf(entries), errors.isEmpty() ? null : String.join("; ", errors)));
		}

		private static List<Node> named(List<Node> nodes) {
			List<Node> out = new ArrayList<>();
			for (Node n : nodes)
				if (n.childText("LEDGERNAME") != null)
					out.add(n);
			return out;
		}

		private static List<Node> allocations(List<Node> inventoryEntries) {
			List<Node> out = new ArrayList<>();
			for (Node inv : inventoryEntries)
				out.addAll(named(inv.children("ACCOUNTINGALLOCATIONS.LIST")));
			return out;
		}
	}
}
