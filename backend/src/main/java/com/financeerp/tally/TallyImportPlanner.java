package com.financeerp.tally;

import com.financeerp.india.IndiaTax;
import com.financeerp.india.LedgerPoster;
import com.financeerp.tally.TallyGroups.Category;
import com.financeerp.tally.TallyGroups.Resolution;
import com.financeerp.tally.TallyXmlParser.Entry;
import com.financeerp.tally.TallyXmlParser.Group;
import com.financeerp.tally.TallyXmlParser.Ledger;
import com.financeerp.tally.TallyXmlParser.TallyData;
import com.financeerp.tally.TallyXmlParser.Voucher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Decides, without touching the database, how a parsed Tally file maps onto this company's accounts and parties and
 * which vouchers can become DRAFT journals. The preview and the import share this plan so they always agree.
 */
final class TallyImportPlanner {
	static final int MAX_LISTED = 500;
	static final String MATCHED_ACCOUNT = "MATCHED_ACCOUNT", NEW_ACCOUNT = "NEW_ACCOUNT", MATCHED_PARTY = "MATCHED_PARTY",
			NEW_PARTY = "NEW_PARTY", UNMAPPED = "UNMAPPED";
	private static final Set<String> ACCOUNT_TYPES = Set.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE");
	private static final Set<String> ACCOUNTING_VOUCHER_TYPES = Set.of("contra", "payment", "receipt", "journal", "sales",
			"purchase", "credit note", "debit note", "payroll");
	private static final Set<String> NON_ACCOUNTING_VOUCHER_TYPES = Set.of("memorandum", "reversing journal",
			"delivery note", "receipt note", "rejections in", "rejections out", "stock journal", "physical stock",
			"purchase order", "sales order", "material in", "material out", "job work in order", "job work out order",
			"attendance");

	private TallyImportPlanner() {
	}

	record ExistingAccount(long id, String code, String name, String accountType) {
	}

	record ExistingParty(long id, String partyType, String name, String gstin) {
	}

	/** accounts and parties are the company's active records; importedKeys maps idempotency keys to journal ids. */
	record Context(List<ExistingAccount> accounts, List<ExistingParty> parties, Map<String, Long> importedKeys,
			String companyStateCode, Long arAccountId, Long apAccountId) {
	}

	static final class PartyPlan {
		Long id;
		final String partyType, name, gstin, pan, stateCode;
		final boolean isNew;

		PartyPlan(Long id, String partyType, String name, String gstin, String stateCode, boolean isNew) {
			this.id = id;
			this.partyType = partyType;
			this.name = name;
			this.gstin = gstin;
			this.pan = gstin == null ? null : gstin.substring(2, 12);
			this.stateCode = stateCode;
			this.isNew = isNew;
		}
	}

	static final class LedgerPlan {
		final String name;
		final boolean inMasters;
		String tallyGroup, primaryGroup, category, action, accountType, accountCode, accountName, reason, override;
		String gstinRaw, stateName;
		Long accountId;
		PartyPlan party;
		BigDecimal openingBalance;
		int voucherCount;
		final List<String> warnings = new ArrayList<>();

		LedgerPlan(String name, boolean inMasters) {
			this.name = name;
			this.inMasters = inMasters;
		}

		boolean mapped() {
			return !UNMAPPED.equals(action);
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ledgerName", name);
			m.put("inMasters", inMasters);
			m.put("tallyGroup", tallyGroup);
			m.put("primaryGroup", primaryGroup);
			m.put("category", category);
			m.put("action", action);
			m.put("override", override);
			m.put("accountId", accountId);
			m.put("accountCode", accountCode);
			m.put("accountName", accountName);
			m.put("accountType", accountType);
			if (party != null) {
				m.put("partyId", party.id);
				m.put("partyName", party.name);
				m.put("partyType", party.partyType);
				m.put("gstin", party.gstin);
				m.put("stateCode", party.stateCode);
			}
			m.put("voucherCount", voucherCount);
			m.put("reason", reason);
			m.put("warnings", warnings);
			return m;
		}
	}

	enum Status {
		IMPORT, SKIP, FAIL
	}

	record VoucherPlan(Voucher voucher, String key, Status status, String reason) {
		Map<String, Object> ref() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("voucherType", voucher.voucherType());
			m.put("voucherNumber", voucher.number());
			m.put("date", voucher.date() != null ? voucher.date().toString() : voucher.rawDate());
			m.put("guid", voucher.guid());
			return m;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = ref();
			m.put("status", status.name());
			m.put("reason", reason);
			return m;
		}

		String description() {
			StringBuilder d = new StringBuilder("Tally ").append(voucher.voucherType());
			if (voucher.number() != null)
				d.append(" #").append(voucher.number());
			if (voucher.narration() != null && !voucher.narration().isBlank())
				d.append(": ").append(voucher.narration().replaceAll("\\s+", " ").trim());
			return truncate(d.toString(), 500);
		}
	}

	static final class Plan {
		final TallyData data;
		final Map<String, LedgerPlan> ledgers = new LinkedHashMap<>();
		final List<LedgerPlan> newAccounts = new ArrayList<>();
		final List<PartyPlan> newParties = new ArrayList<>();
		final List<VoucherPlan> vouchers = new ArrayList<>();
		final List<Map<String, Object>> problems = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		int problemCount;

		Plan(TallyData data) {
			this.data = data;
		}

		void problem(String code, String message, String ledgerName, VoucherPlan voucher) {
			problemCount++;
			if (problems.size() >= MAX_LISTED)
				return;
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("code", code);
			p.put("message", message);
			if (ledgerName != null)
				p.put("ledgerName", ledgerName);
			if (voucher != null)
				p.put("voucher", voucher.ref());
			problems.add(p);
		}

		LedgerPlan ledger(String name) {
			return ledgers.get(key(name));
		}

		long count(Status s) {
			return vouchers.stream().filter(v -> v.status() == s).count();
		}

		/** Journal lines for an importable voucher; account and party ids must have been assigned already. */
		List<LedgerPoster.Line> lines(VoucherPlan v) {
			List<LedgerPoster.Line> out = new ArrayList<>();
			for (Entry e : v.voucher().entries()) {
				if (e.amount().signum() == 0)
					continue;
				LedgerPlan lp = ledger(e.ledgerName());
				if (lp.accountId == null)
					throw new IllegalStateException("Ledger " + lp.name + " has no account.");
				String description = lp.party != null ? lp.party.name : null;
				BigDecimal abs = e.amount().abs();
				out.add(e.debit() ? new LedgerPoster.Line(lp.accountId, description, abs, BigDecimal.ZERO)
						: new LedgerPoster.Line(lp.accountId, description, BigDecimal.ZERO, abs));
			}
			return out;
		}

		Map<String, Object> preview() {
			Map<String, Object> out = new LinkedHashMap<>();
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("groups", data.groups().size());
			summary.put("ledgers", ledgers.size());
			for (String a : List.of(MATCHED_ACCOUNT, NEW_ACCOUNT, MATCHED_PARTY, NEW_PARTY, UNMAPPED))
				summary.put(camel(a), ledgers.values().stream().filter(l -> a.equals(l.action)).count());
			summary.put("partiesToCreate", newParties.size());
			summary.put("vouchers", vouchers.size());
			summary.put("importable", count(Status.IMPORT));
			summary.put("alreadyImported", vouchers.stream()
					.filter(v -> v.status() == Status.SKIP && v.reason().startsWith("already imported")).count());
			summary.put("skipped", count(Status.SKIP));
			summary.put("failed", count(Status.FAIL));
			out.put("summary", summary);
			out.put("ledgers", ledgers.values().stream().map(LedgerPlan::toMap).toList());
			out.put("vouchers", voucherStats());
			out.put("voucherIssues", vouchers.stream().filter(v -> v.status() != Status.IMPORT).limit(MAX_LISTED)
					.map(VoucherPlan::toMap).toList());
			out.put("openingBalances", openingBalances());
			out.put("problems", problems);
			out.put("problemCount", problemCount);
			out.put("warnings", warnings);
			return out;
		}

		private Map<String, Object> voucherStats() {
			Map<String, Map<String, Object>> byType = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			LocalDate from = null, to = null;
			for (VoucherPlan v : vouchers) {
				Map<String, Object> t = byType.computeIfAbsent(v.voucher().voucherType(), k -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("voucherType", k);
					m.put("count", 0L);
					m.put("importable", 0L);
					m.put("skipped", 0L);
					m.put("failed", 0L);
					m.put("from", null);
					m.put("to", null);
					return m;
				});
				t.merge("count", 1L, (a, b) -> (Long) a + (Long) b);
				String bucket = switch (v.status()) {
				case IMPORT -> "importable";
				case SKIP -> "skipped";
				case FAIL -> "failed";
				};
				t.merge(bucket, 1L, (a, b) -> (Long) a + (Long) b);
				LocalDate d = v.voucher().date();
				if (d != null) {
					if (t.get("from") == null || d.isBefore((LocalDate) t.get("from")))
						t.put("from", d);
					if (t.get("to") == null || d.isAfter((LocalDate) t.get("to")))
						t.put("to", d);
					from = from == null || d.isBefore(from) ? d : from;
					to = to == null || d.isAfter(to) ? d : to;
				}
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("total", vouchers.size());
			out.put("from", from);
			out.put("to", to);
			out.put("byType", new ArrayList<>(byType.values()));
			return out;
		}

		private Map<String, Object> openingBalances() {
			List<Map<String, Object>> rows = new ArrayList<>();
			BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
			for (LedgerPlan l : ledgers.values()) {
				if (l.openingBalance == null || l.openingBalance.signum() == 0)
					continue;
				boolean debit = l.openingBalance.signum() < 0;
				if (debit)
					dr = dr.add(l.openingBalance.negate());
				else
					cr = cr.add(l.openingBalance);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("ledgerName", l.name);
				m.put("amount", l.openingBalance.abs());
				m.put("side", debit ? "Dr" : "Cr");
				rows.add(m);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("note", "Reported only. Opening balances are not posted by the Tally import.");
			out.put("totalDebit", dr);
			out.put("totalCredit", cr);
			out.put("ledgers", rows);
			return out;
		}
	}

	static Plan plan(TallyData data, Context ctx, Map<String, Object> overrides) {
		Plan p = new Plan(data);
		p.warnings.addAll(data.warnings());
		Map<String, Group> groups = TallyGroups.index(data.groups());
		for (Ledger l : data.ledgers()) {
			String k = key(l.name());
			if (p.ledgers.containsKey(k)) {
				p.warnings.add("Ledger '" + l.name() + "' appears more than once; the first definition was used.");
				continue;
			}
			LedgerPlan lp = new LedgerPlan(l.name(), true);
			lp.tallyGroup = l.parent();
			lp.openingBalance = l.openingBalance();
			lp.gstinRaw = l.gstin();
			lp.stateName = l.stateName();
			p.ledgers.put(k, lp);
		}
		for (Voucher v : data.vouchers()) {
			Set<String> seen = new HashSet<>();
			for (Entry e : v.entries())
				if (seen.add(key(e.ledgerName())))
					p.ledgers.computeIfAbsent(key(e.ledgerName()), k -> new LedgerPlan(e.ledgerName(), false)).voucherCount++;
		}
		Map<String, Object> ov = new HashMap<>();
		if (overrides != null)
			overrides.forEach((name, value) -> {
				if (name == null || value == null)
					return;
				if (!p.ledgers.containsKey(key(name)))
					p.warnings.add("Mapping for '" + name + "' was ignored: no such ledger in the file.");
				else
					ov.put(key(name), value);
			});
		new Mapper(p, ctx, groups, ov).run();
		planVouchers(p, ctx, data);
		return p;
	}

	private static final class Mapper {
		final Plan p;
		final Context ctx;
		final Map<String, Group> groups;
		final Map<String, Object> overrides;
		final Map<Long, ExistingAccount> accountsById = new HashMap<>();
		final Map<String, List<ExistingAccount>> accountsByName = new HashMap<>();
		final Map<String, ExistingParty> partiesByGstin = new HashMap<>();
		final Map<String, List<ExistingParty>> partiesByName = new HashMap<>();
		final Map<String, PartyPlan> proposedByGstin = new HashMap<>();
		final Map<String, PartyPlan> proposedByName = new HashMap<>();
		final Map<String, String> statesByName = new HashMap<>();

		Mapper(Plan p, Context ctx, Map<String, Group> groups, Map<String, Object> overrides) {
			this.p = p;
			this.ctx = ctx;
			this.groups = groups;
			this.overrides = overrides;
			List<ExistingAccount> sorted = new ArrayList<>(ctx.accounts());
			sorted.sort(Comparator.comparing(ExistingAccount::code));
			for (ExistingAccount a : sorted) {
				accountsById.put(a.id(), a);
				accountsByName.computeIfAbsent(nameKey(a.name()), k -> new ArrayList<>()).add(a);
			}
			for (ExistingParty party : ctx.parties()) {
				if (party.gstin() != null)
					partiesByGstin.putIfAbsent(party.partyType() + "|" + party.gstin(), party);
				partiesByName.computeIfAbsent(nameKey(party.name()), k -> new ArrayList<>()).add(party);
			}
			IndiaTax.STATES.forEach((code, name) -> statesByName.put(stateKey(name), code));
		}

		void run() {
			List<LedgerPlan> sorted = new ArrayList<>(p.ledgers.values());
			sorted.sort(Comparator.comparing(l -> l.name.toLowerCase(Locale.ROOT)));
			for (LedgerPlan lp : sorted)
				decide(lp);
			Set<String> codes = new HashSet<>();
			for (ExistingAccount a : ctx.accounts())
				codes.add(a.code());
			int[] next = new int[6];
			for (LedgerPlan lp : p.newAccounts) {
				int digit = List.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE").indexOf(lp.accountType) + 1;
				String code;
				do
					code = String.format("TLY-%d-%03d", digit, ++next[digit]);
				while (!codes.add(code));
				lp.accountCode = code;
			}
		}

		private void decide(LedgerPlan lp) {
			Resolution res = lp.inMasters ? TallyGroups.resolve(lp.tallyGroup, groups) : null;
			if (res != null) {
				lp.primaryGroup = res.primaryGroup();
				lp.category = res.category().name();
				if (res.note() != null && res.category() != Category.MANUAL)
					lp.warnings.add(res.note());
			}
			Object override = overrides.get(key(lp.name));
			if (override != null) {
				applyOverride(lp, override);
				return;
			}
			Category c = res == null ? null : res.category();
			if (c == Category.CUSTOMER || c == Category.VENDOR) {
				party(lp, c.name());
				return;
			}
			if (c != null && c.isAccountType()) {
				account(lp, c.name(), false);
				return;
			}
			ExistingAccount byName = first(accountsByName.get(nameKey(lp.name)), null);
			if (byName != null) {
				matched(lp, byName);
				lp.warnings.add("Matched an existing account by name.");
				return;
			}
			if (res == null) {
				List<ExistingParty> parties = partiesByName.getOrDefault(nameKey(lp.name), List.of());
				if (!parties.isEmpty() && parties.stream().map(ExistingParty::partyType).distinct().count() == 1) {
					party(lp, parties.get(0).partyType());
					return;
				}
				unmapped(lp, "The ledger master is not in the file and no account or party has this name.");
			} else
				unmapped(lp, res.note());
		}

		private void applyOverride(LedgerPlan lp, Object value) {
			String text = value instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(value).trim();
			String upper = text.toUpperCase(Locale.ROOT);
			lp.override = upper;
			if (upper.matches("[0-9]{1,18}")) {
				ExistingAccount a = accountsById.get(Long.parseLong(upper));
				if (a == null)
					throw new IllegalArgumentException("Mapping for ledger '" + lp.name + "' refers to account " + upper
							+ ", which is not an active account of this company.");
				matched(lp, a);
			} else if (ACCOUNT_TYPES.contains(upper))
				account(lp, upper, true);
			else if (upper.equals("CUSTOMER") || upper.equals("VENDOR"))
				party(lp, upper);
			else
				throw new IllegalArgumentException("Mapping for ledger '" + lp.name
						+ "' must be an account id, an account type (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE), CUSTOMER or VENDOR.");
		}

		private void account(LedgerPlan lp, String type, boolean strictType) {
			List<ExistingAccount> sameName = accountsByName.get(nameKey(lp.name));
			ExistingAccount a = first(sameName, type);
			if (a == null && !strictType)
				a = first(sameName, null);
			if (a != null) {
				matched(lp, a);
				if (!a.accountType().equals(type))
					lp.warnings.add("Existing account '" + a.name() + "' is " + a.accountType()
							+ " but the Tally group suggests " + type + ".");
				return;
			}
			if (sameName != null)
				lp.warnings.add("An account with this name already exists with a different type; a new " + type
						+ " account will be created.");
			lp.action = NEW_ACCOUNT;
			lp.accountType = type;
			lp.accountName = truncate(lp.name, 180);
			p.newAccounts.add(lp);
		}

		private void matched(LedgerPlan lp, ExistingAccount a) {
			lp.action = MATCHED_ACCOUNT;
			lp.accountId = a.id();
			lp.accountCode = a.code();
			lp.accountName = a.name();
			lp.accountType = a.accountType();
		}

		private void party(LedgerPlan lp, String partyType) {
			boolean customer = partyType.equals("CUSTOMER");
			Long control = customer ? ctx.arAccountId() : ctx.apAccountId();
			if (control == null) {
				unmapped(lp, "System account " + (customer ? "AR" : "AP")
						+ " is missing. Run the India chart-of-accounts setup first.");
				return;
			}
			String gstin = lp.gstinRaw == null ? null : lp.gstinRaw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
			if (gstin != null && gstin.isEmpty())
				gstin = null;
			if (gstin != null && !IndiaTax.isValidGstin(gstin)) {
				lp.warnings.add("GSTIN '" + gstin + "' is invalid (format or checksum); the party is used without a GSTIN.");
				p.problem("INVALID_GSTIN", "Ledger '" + lp.name + "' has an invalid GSTIN '" + gstin
						+ "'; the party is created without a GSTIN.", lp.name, null);
				gstin = null;
			}
			ExistingAccount controlAccount = accountsById.get(control);
			lp.accountId = control;
			lp.accountType = customer ? "ASSET" : "LIABILITY";
			if (controlAccount != null) {
				lp.accountCode = controlAccount.code();
				lp.accountName = controlAccount.name();
				lp.accountType = controlAccount.accountType();
			}
			ExistingParty existing = gstin == null ? null : partiesByGstin.get(partyType + "|" + gstin);
			if (existing == null)
				existing = partiesByName.getOrDefault(nameKey(lp.name), List.of()).stream()
						.filter(x -> x.partyType().equals(partyType)).findFirst().orElse(null);
			if (existing != null) {
				lp.action = MATCHED_PARTY;
				lp.party = new PartyPlan(existing.id(), existing.partyType(), existing.name(), existing.gstin(), null, false);
				return;
			}
			PartyPlan proposed = gstin == null ? null : proposedByGstin.get(partyType + "|" + gstin);
			if (proposed == null)
				proposed = proposedByName.get(partyType + "|" + nameKey(lp.name));
			if (proposed != null) {
				lp.action = NEW_PARTY;
				lp.party = proposed;
				lp.warnings.add("Shares the new party '" + proposed.name + "' (same GSTIN or name).");
				return;
			}
			String state = gstin != null ? gstin.substring(0, 2) : null;
			if (state == null && lp.stateName != null) {
				state = statesByName.get(stateKey(lp.stateName));
				if (state == null)
					lp.warnings.add("State '" + lp.stateName + "' was not recognised; the company's state is used.");
			}
			if (state == null)
				state = ctx.companyStateCode();
			if (state == null) {
				unmapped(lp, "The party's GST state is unknown (no valid GSTIN or state) and the company has no GST state. Set the India profile first.");
				return;
			}
			proposed = new PartyPlan(null, partyType, truncate(lp.name, 180), gstin, state, true);
			if (gstin != null)
				proposedByGstin.put(partyType + "|" + gstin, proposed);
			proposedByName.put(partyType + "|" + nameKey(lp.name), proposed);
			p.newParties.add(proposed);
			lp.action = NEW_PARTY;
			lp.party = proposed;
		}

		private void unmapped(LedgerPlan lp, String reason) {
			lp.action = UNMAPPED;
			lp.accountId = null;
			lp.accountCode = null;
			lp.accountName = null;
			lp.party = null;
			lp.reason = reason;
			p.problem("UNMAPPED_LEDGER", "Ledger '" + lp.name + "' is not mapped: " + reason
					+ " Provide a mapping (account id or type) to import its vouchers.", lp.name, null);
		}

		private static ExistingAccount first(List<ExistingAccount> list, String type) {
			if (list == null)
				return null;
			for (ExistingAccount a : list)
				if (type == null || a.accountType().equals(type))
					return a;
			return null;
		}
	}

	private static void planVouchers(Plan p, Context ctx, TallyData data) {
		Map<String, String> typeParents = new HashMap<>();
		for (TallyXmlParser.VoucherType t : data.voucherTypes())
			typeParents.putIfAbsent(TallyGroups.normalize(t.name()), t.parent());
		Map<String, Integer> occurrences = new HashMap<>();
		Set<String> guidKeys = new HashSet<>();
		for (Voucher v : data.vouchers()) {
			String key;
			boolean duplicateGuid = false;
			if (v.guid() != null) {
				key = idempotencyKey(v.guid());
				duplicateGuid = !guidKeys.add(key);
			} else {
				String material = v.voucherType() + "|" + (v.number() == null ? "" : v.number()) + "|"
						+ (v.date() != null ? v.date().format(DateTimeFormatter.BASIC_ISO_DATE) : v.rawDate());
				int n = occurrences.merge(material, 1, Integer::sum);
				if (n > 1) {
					material = material + "|#" + n;
					p.warnings.add("Voucher " + v.voucherType() + " " + (v.number() == null ? "(no number)" : v.number())
							+ " on " + v.date() + " has no GUID and repeats type, number and date; it is keyed by its position in the file.");
				}
				key = "TALLY:" + sha256(material);
			}
			VoucherPlan plan = classify(p, ctx, v, key, duplicateGuid, typeParents);
			p.vouchers.add(plan);
			if (plan.status() == Status.FAIL)
				p.problem(plan.reason().startsWith("unbalanced") ? "UNBALANCED_VOUCHER" : "VOUCHER_FAILED",
						plan.reason(), null, plan);
			else if (plan.status() == Status.SKIP && plan.reason().startsWith("inventory-only"))
				p.problem("INVENTORY_ONLY_SKIPPED", plan.reason(), null, plan);
		}
	}

	private static VoucherPlan classify(Plan p, Context ctx, Voucher v, String key, boolean duplicateGuid,
			Map<String, String> typeParents) {
		if (v.deleted())
			return new VoucherPlan(v, key, Status.SKIP, "marked for deletion in Tally");
		if (v.cancelled())
			return new VoucherPlan(v, key, Status.SKIP, "cancelled voucher");
		if (v.optional())
			return new VoucherPlan(v, key, Status.SKIP, "optional (memorandum) voucher");
		if (nonAccounting(v.voucherType(), typeParents))
			return new VoucherPlan(v, key, Status.SKIP, "non-accounting voucher type '" + v.voucherType() + "'");
		if (v.entries().isEmpty())
			return new VoucherPlan(v, key, Status.SKIP, "inventory-only voucher (no ledger entries)");
		if (duplicateGuid)
			return new VoucherPlan(v, key, Status.SKIP, "duplicate of an earlier voucher in this file (same GUID)");
		Long existing = ctx.importedKeys().get(key);
		if (existing != null)
			return new VoucherPlan(v, key, Status.SKIP, "already imported as journal " + existing);
		if (v.parseError() != null)
			return new VoucherPlan(v, key, Status.FAIL, v.parseError());
		List<String> reasons = new ArrayList<>();
		if (v.total().signum() != 0)
			reasons.add("unbalanced: debits " + v.debits().toPlainString() + ", credits " + v.credits().toPlainString());
		Set<String> unmapped = new TreeSet<>();
		long nonZero = 0;
		for (Entry e : v.entries()) {
			if (e.amount().signum() != 0)
				nonZero++;
			LedgerPlan lp = p.ledger(e.ledgerName());
			if (lp == null || !lp.mapped())
				unmapped.add(e.ledgerName());
		}
		if (!unmapped.isEmpty())
			reasons.add("unmapped ledger(s): " + String.join(", ", unmapped));
		if (reasons.isEmpty() && nonZero < 2)
			reasons.add("fewer than two non-zero ledger amounts");
		return reasons.isEmpty() ? new VoucherPlan(v, key, Status.IMPORT, null)
				: new VoucherPlan(v, key, Status.FAIL, String.join("; ", reasons));
	}

	static boolean nonAccounting(String voucherType, Map<String, String> typeParents) {
		String current = voucherType;
		for (int i = 0; i < 20 && current != null; i++) {
			String k = TallyGroups.normalize(current);
			if (NON_ACCOUNTING_VOUCHER_TYPES.contains(k))
				return true;
			if (ACCOUNTING_VOUCHER_TYPES.contains(k))
				return false;
			current = typeParents.get(k);
		}
		return false;
	}

	/** "TALLY:" + GUID; GUIDs that would not fit VARCHAR(100) are replaced by their SHA-256 so keys never collide. */
	static String idempotencyKey(String guid) {
		String k = "TALLY:" + guid.trim();
		return k.length() <= 100 ? k : "TALLY:SHA256:" + sha256(guid.trim());
	}

	static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String key(String ledgerName) {
		return ledgerName.trim().toLowerCase(Locale.ROOT);
	}

	private static String nameKey(String name) {
		return truncate(name.trim(), 180).toLowerCase(Locale.ROOT);
	}

	private static String stateKey(String name) {
		return name.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z]+", " ").trim();
	}

	static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static String camel(String action) {
		String[] parts = action.toLowerCase(Locale.ROOT).split("_");
		StringBuilder b = new StringBuilder(parts[0]);
		for (int i = 1; i < parts.length; i++)
			b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		return b.toString();
	}
}
