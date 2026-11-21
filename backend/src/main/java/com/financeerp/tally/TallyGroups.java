package com.financeerp.tally;

import com.financeerp.tally.TallyXmlParser.Group;
import java.util.*;

/** Tally's 28 predefined groups and how a ledger's group chain maps onto this ledger's account types. */
public final class TallyGroups {
	public enum Category {
		ASSET, LIABILITY, EQUITY, INCOME, EXPENSE, CUSTOMER, VENDOR, MANUAL;

		public boolean isAccountType() {
			return this != CUSTOMER && this != VENDOR && this != MANUAL;
		}
	}

	/** primaryGroup is the predefined Tally group the chain ended at (null when it could not be resolved). */
	public record Resolution(String primaryGroup, Category category, String note) {
	}

	private static final Map<String, String> CANONICAL = new LinkedHashMap<>();
	private static final Map<String, Category> CATEGORY = new HashMap<>();

	static {
		predefined(Category.ASSET, "Bank Accounts", "Cash-in-Hand", "Current Assets", "Fixed Assets", "Investments",
				"Loans & Advances (Asset)", "Deposits (Asset)", "Stock-in-Hand", "Misc. Expenses (ASSET)");
		predefined(Category.LIABILITY, "Duties & Taxes", "Current Liabilities", "Provisions", "Loans (Liability)",
				"Secured Loans", "Unsecured Loans", "Bank OD A/c");
		predefined(Category.EQUITY, "Capital Account", "Reserves & Surplus");
		predefined(Category.INCOME, "Sales Accounts", "Direct Incomes", "Indirect Incomes");
		predefined(Category.EXPENSE, "Purchase Accounts", "Direct Expenses", "Indirect Expenses");
		predefined(Category.CUSTOMER, "Sundry Debtors");
		predefined(Category.VENDOR, "Sundry Creditors");
		predefined(Category.MANUAL, "Branch / Divisions", "Suspense A/c");
		// Tally's built-in aliases for some predefined groups.
		alias("Bank OCC A/c", "Bank OD A/c");
		alias("Retained Earnings", "Reserves & Surplus");
		alias("Income (Direct)", "Direct Incomes");
		alias("Income (Indirect)", "Indirect Incomes");
		alias("Expenses (Direct)", "Direct Expenses");
		alias("Expenses (Indirect)", "Indirect Expenses");
	}

	private TallyGroups() {
	}

	private static void predefined(Category c, String... names) {
		for (String n : names) {
			CANONICAL.put(normalize(n), n);
			CATEGORY.put(normalize(n), c);
		}
	}

	private static void alias(String alias, String canonical) {
		CANONICAL.put(normalize(alias), canonical);
		CATEGORY.put(normalize(alias), CATEGORY.get(normalize(canonical)));
	}

	/** The 28 predefined group names in Tally's spelling. */
	public static List<String> predefinedGroups() {
		return CANONICAL.values().stream().distinct().toList();
	}

	public static String normalize(String name) {
		if (name == null)
			return "";
		return name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ").replaceAll("\\s*/\\s*", "/").trim();
	}

	public static boolean isPredefined(String name) {
		return CATEGORY.containsKey(normalize(name));
	}

	public static Map<String, Group> index(List<Group> groups) {
		Map<String, Group> out = new HashMap<>();
		for (Group g : groups)
			out.putIfAbsent(normalize(g.name()), g);
		return out;
	}

	/**
	 * Walks PARENT links from a ledger's parent group until a predefined group is reached. A group whose RESERVEDNAME
	 * is a predefined name counts as that group (Tally lets users rename predefined groups). A user-created primary
	 * group is classified by its nature flags (ISREVENUE / ISDEEMEDPOSITIVE) when present.
	 */
	public static Resolution resolve(String ledgerParent, Map<String, Group> groups) {
		String current = ledgerParent;
		Set<String> seen = new HashSet<>();
		Group last = null;
		while (current != null && !normalize(current).isEmpty()) {
			String key = normalize(current);
			if (key.equals("primary"))
				return nature(last);
			Group g = groups.get(key);
			if (g != null && g.reservedName() != null && isPredefined(g.reservedName()))
				return known(g.reservedName());
			if (isPredefined(current))
				return known(current);
			if (g == null)
				return new Resolution(null, Category.MANUAL,
						"Group '" + current + "' is not in the file and is not a Tally predefined group.");
			if (!seen.add(key) || seen.size() > 100)
				return new Resolution(null, Category.MANUAL, "Group '" + current + "' has a circular parent chain.");
			last = g;
			current = g.parent();
		}
		return nature(last);
	}

	private static Resolution known(String name) {
		String key = normalize(name);
		Category c = CATEGORY.get(key);
		String canonical = CANONICAL.get(key);
		return new Resolution(canonical, c,
				c == Category.MANUAL ? "Tally group '" + canonical + "' needs a manual mapping." : null);
	}

	private static Resolution nature(Group primary) {
		if (primary == null)
			return new Resolution(null, Category.MANUAL, "Ledger has no Tally group.");
		if (primary.revenue() != null && primary.deemedPositive() != null) {
			Category c = primary.revenue() ? (primary.deemedPositive() ? Category.EXPENSE : Category.INCOME)
					: (primary.deemedPositive() ? Category.ASSET : Category.LIABILITY);
			return new Resolution(primary.name(), c, "Classified by the nature of user-defined primary group '"
					+ primary.name() + "'.");
		}
		return new Resolution(null, Category.MANUAL,
				"User-defined primary group '" + primary.name() + "' has no recognisable nature.");
	}

	/** Tally group used when exporting an account as a LEDGER master. */
	public static String exportParent(String accountType, String systemKey) {
		if (systemKey != null) {
			switch (systemKey) {
			case "AR":
				return "Sundry Debtors";
			case "AP":
				return "Sundry Creditors";
			case "BANK":
				return "Bank Accounts";
			case "SALES", "SALES_RETURNS":
				return "Sales Accounts";
			case "PURCHASES":
				return "Purchase Accounts";
			case "FIXED_ASSETS", "ACCUMULATED_DEPRECIATION":
				return "Fixed Assets";
			default:
				if (systemKey.matches("(INPUT|OUTPUT|RCM|TDS|TCS)_.*"))
					return "Duties & Taxes";
			}
		}
		return switch (accountType) {
		case "ASSET" -> "Current Assets";
		case "LIABILITY" -> "Current Liabilities";
		case "EQUITY" -> "Capital Account";
		case "INCOME" -> "Indirect Incomes";
		default -> "Indirect Expenses";
		};
	}
}
