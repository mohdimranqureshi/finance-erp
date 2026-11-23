package com.financeerp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeerp.domain.TallyGroups;
import com.financeerp.domain.TallyImportPlanner;
import com.financeerp.domain.TallyImportPlanner.*;
import com.financeerp.domain.TallyXmlParser;
import com.financeerp.domain.TallyXmlParser.TallyData;
import com.financeerp.domain.TallyXmlWriter;
import com.financeerp.domain.TallyXmlWriter.*;
import com.financeerp.dto.TallyDto;
import com.financeerp.repository.TallyRepository;
import com.financeerp.repository.TallyRepository.ExportRow;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Tally XML interchange. Import creates missing accounts and parties and one DRAFT journal per balanced voucher (never
 * approved or posted here); export writes POSTED journals as Tally "Journal" vouchers.
 */
@Service
public class TallyService {
	private static final Set<String> WRITERS = Set.of("OWNER", "ACCOUNTANT");
	private static final int MAX_EXPORT_JOURNALS = 50_000;
	private static final int MAX_MAPPING_CHARS = 1_000_000;
	private final TallyRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final ObjectMapper mapper;

	public TallyService(TallyRepository repository, CompanyAccess access, LedgerPoster poster, ObjectMapper mapper) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.mapper = mapper;
	}

	public Map<String, Object> preview(long companyId, TallyDto.ImportUpload upload) {
		access.requireRole(companyId, WRITERS);
		TallyData data = TallyXmlParser.parse(read(upload.content()));
		Plan plan = TallyImportPlanner.plan(data, context(companyId), overrides(upload));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fileName", safeName(upload.fileName()));
		out.putAll(plan.preview());
		return out;
	}

	@Transactional
	public Map<String, Object> importFile(long companyId, TallyDto.ImportUpload upload) {
		long actorId = access.requireRole(companyId, WRITERS);
		TallyData data = TallyXmlParser.parse(read(upload.content()));
		Map<String, Object> overrides = overrides(upload);
		// Serialise imports per company so two uploads of the same file cannot race on idempotency keys or new codes.
		repository.lockImports(companyId);
		Plan plan = TallyImportPlanner.plan(data, context(companyId), overrides);

		List<Map<String, Object>> accounts = new ArrayList<>();
		for (LedgerPlan lp : plan.newAccounts) {
			lp.accountId = repository.insertAccount(companyId, lp.accountCode, lp.accountName, lp.accountType);
			accounts.add(Map.of("id", lp.accountId, "code", lp.accountCode, "name", lp.accountName, "accountType",
					lp.accountType, "ledgerName", lp.name));
		}
		List<Map<String, Object>> parties = new ArrayList<>();
		for (PartyPlan pp : plan.newParties) {
			pp.id = repository.insertParty(companyId, pp.partyType, pp.name, pp.gstin, pp.pan, pp.stateCode);
			poster.audit(companyId, "PARTY_CREATED", "PARTY", pp.id, pp.partyType + " (Tally import)");
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", pp.id);
			m.put("partyType", pp.partyType);
			m.put("name", pp.name);
			m.put("gstin", pp.gstin);
			m.put("stateCode", pp.stateCode);
			parties.add(m);
		}
		List<Map<String, Object>> created = new ArrayList<>();
		for (VoucherPlan v : plan.vouchers) {
			if (v.status() != Status.IMPORT)
				continue;
			long journalId = poster.createDraftJournal(companyId, v.voucher().date(), v.description(), v.key(), actorId,
					plan.lines(v));
			Map<String, Object> m = v.ref();
			m.put("journalId", journalId);
			created.add(m);
		}
		long skipped = plan.count(Status.SKIP), failed = plan.count(Status.FAIL);
		poster.audit(companyId, "TALLY_IMPORTED", "COMPANY", companyId,
				"file=" + safeName(upload.fileName()) + "; accounts=" + accounts.size() + "; parties="
						+ parties.size() + "; journals=" + created.size() + "; skipped=" + skipped + "; failed=" + failed);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fileName", safeName(upload.fileName()));
		out.put("accountsCreated", accounts.size());
		out.put("partiesCreated", parties.size());
		out.put("journalsCreated", created.size());
		out.put("skipped", skipped);
		out.put("failed", failed);
		out.put("accounts", accounts);
		out.put("parties", parties);
		out.put("journals", created.size() > TallyImportPlanner.MAX_LISTED
				? created.subList(0, TallyImportPlanner.MAX_LISTED) : created);
		out.put("skippedVouchers", plan.vouchers.stream().filter(v -> v.status() == Status.SKIP)
				.limit(TallyImportPlanner.MAX_LISTED).map(VoucherPlan::toMap).toList());
		out.put("failedVouchers", plan.vouchers.stream().filter(v -> v.status() == Status.FAIL)
				.limit(TallyImportPlanner.MAX_LISTED).map(VoucherPlan::toMap).toList());
		out.put("problems", plan.problems);
		out.put("warnings", plan.warnings);
		out.put("note", "Journals are DRAFT. Submit, approve and post them through the journal workflow. Opening balances were not imported.");
		return out;
	}

	/** Returns the Tally XML document for the posted journals in the range. */
	public String export(long companyId, LocalDate from, LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("from must be on or before to.");
		Integer journals = repository.countPostedJournals(companyId, from, to);
		if (journals != null && journals > MAX_EXPORT_JOURNALS)
			throw new IllegalArgumentException("More than " + MAX_EXPORT_JOURNALS
					+ " posted journals in this range. Export a shorter period.");
		List<ExportRow> rows = repository.findPostedJournalLines(companyId, from, to);
		Map<Long, ExportRow> accounts = new LinkedHashMap<>();
		for (ExportRow r : rows)
			accounts.putIfAbsent(r.accountId(), r);
		List<ExportRow> sortedAccounts = new ArrayList<>(accounts.values());
		sortedAccounts.sort(Comparator.comparing(ExportRow::code));
		Map<Long, String> names = TallyXmlWriter.ledgerNames(
				sortedAccounts.stream().map(r -> new AccountRef(r.accountId(), r.code(), r.name())).toList());
		List<LedgerOut> ledgers = sortedAccounts.stream()
				.map(r -> new LedgerOut(names.get(r.accountId()), TallyGroups.exportParent(r.type(), r.systemKey())))
				.toList();
		Map<Long, List<ExportRow>> byJournal = new LinkedHashMap<>();
		for (ExportRow r : rows)
			byJournal.computeIfAbsent(r.journalId(), k -> new ArrayList<>()).add(r);
		List<VoucherOut> vouchers = new ArrayList<>();
		byJournal.forEach((id, lines) -> vouchers.add(new VoucherOut(String.valueOf(id), lines.get(0).date(),
				lines.get(0).description(), lines.stream()
						.map(r -> new LineOut(names.get(r.accountId()), r.debit(), r.credit())).toList())));
		return TallyXmlWriter.write(ledgers, vouchers);
	}

	private Context context(long companyId) {
		List<ExistingAccount> accounts = repository.findActiveAccounts(companyId);
		List<ExistingParty> parties = repository.findActiveParties(companyId);
		Map<String, Long> keys = repository.findTallyJournalKeys(companyId);
		String state = repository.findCompanyStateCode(companyId);
		return new Context(accounts, parties, keys, state, repository.findActiveSystemAccountId(companyId, "AR"),
				repository.findActiveSystemAccountId(companyId, "AP"));
	}

	private static byte[] read(byte[] content) {
		if (content == null || content.length == 0)
			throw new IllegalArgumentException("The file is empty.");
		if (content.length > TallyXmlParser.MAX_BYTES)
			throw new IllegalArgumentException("The Tally XML file is larger than 10 MB.");
		return content;
	}

	/** "mapping" may arrive as a form field or as a JSON file part: {"Ledger name": 123 | "EXPENSE" | "VENDOR", ...}. */
	private Map<String, Object> overrides(TallyDto.ImportUpload upload) {
		String json = upload.mappingField();
		byte[] part = upload.mappingPart();
		if ((json == null || json.isBlank()) && part != null && part.length > 0) {
			if (part.length > MAX_MAPPING_CHARS)
				throw new IllegalArgumentException("The mapping is too large.");
			json = new String(part, StandardCharsets.UTF_8);
		}
		if (json == null || json.isBlank())
			return Map.of();
		if (json.length() > MAX_MAPPING_CHARS)
			throw new IllegalArgumentException("The mapping is too large.");
		try {
			Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
			return m == null ? Map.of() : m;
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(
					"mapping must be a JSON object of ledger name to account id, account type, CUSTOMER or VENDOR.");
		}
	}

	private static String safeName(String name) {
		String base = name == null ? "" : name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
		return base.length() > 100 ? base.substring(0, 100) : base;
	}
}
