package com.financeerp.tally;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeerp.india.LedgerPoster;
import com.financeerp.security.CompanyAccess;
import com.financeerp.tally.TallyImportPlanner.*;
import com.financeerp.tally.TallyXmlParser.TallyData;
import com.financeerp.tally.TallyXmlWriter.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Tally XML interchange. Import creates missing accounts and parties and one DRAFT journal per balanced voucher (never
 * approved or posted here); export writes POSTED journals as Tally "Journal" vouchers.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tally")
public class TallyController {
	private static final Set<String> WRITERS = Set.of("OWNER", "ACCOUNTANT");
	private static final int MAX_EXPORT_JOURNALS = 50_000;
	private static final int MAX_MAPPING_CHARS = 1_000_000;
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final ObjectMapper mapper;

	public TallyController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.mapper = mapper;
	}

	@PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> preview(@PathVariable long companyId, @RequestParam("file") MultipartFile file,
			MultipartHttpServletRequest request) {
		access.requireRole(companyId, WRITERS);
		TallyData data = TallyXmlParser.parse(read(file));
		Plan plan = TallyImportPlanner.plan(data, context(companyId), overrides(request));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fileName", safeName(file.getOriginalFilename()));
		out.putAll(plan.preview());
		return out;
	}

	@Transactional
	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> importFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file,
			MultipartHttpServletRequest request) {
		long actorId = access.requireRole(companyId, WRITERS);
		TallyData data = TallyXmlParser.parse(read(file));
		Map<String, Object> overrides = overrides(request);
		// Serialise imports per company so two uploads of the same file cannot race on idempotency keys or new codes.
		jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, 0x7A11E1, (int) (companyId % Integer.MAX_VALUE));
		Plan plan = TallyImportPlanner.plan(data, context(companyId), overrides);

		List<Map<String, Object>> accounts = new ArrayList<>();
		for (LedgerPlan lp : plan.newAccounts) {
			lp.accountId = jdbc.queryForObject(
					"INSERT INTO accounts(company_id,code,name,account_type) VALUES (?,?,?,?) RETURNING id", Long.class,
					companyId, lp.accountCode, lp.accountName, lp.accountType);
			accounts.add(Map.of("id", lp.accountId, "code", lp.accountCode, "name", lp.accountName, "accountType",
					lp.accountType, "ledgerName", lp.name));
		}
		List<Map<String, Object>> parties = new ArrayList<>();
		for (PartyPlan pp : plan.newParties) {
			pp.id = jdbc.queryForObject(
					"INSERT INTO parties(company_id,party_type,name,gstin,pan,state_code) VALUES (?,?,?,?,?,?) RETURNING id",
					Long.class, companyId, pp.partyType, pp.name, pp.gstin, pp.pan, pp.stateCode);
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
				"file=" + safeName(file.getOriginalFilename()) + "; accounts=" + accounts.size() + "; parties="
						+ parties.size() + "; journals=" + created.size() + "; skipped=" + skipped + "; failed=" + failed);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fileName", safeName(file.getOriginalFilename()));
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

	@GetMapping("/export")
	public ResponseEntity<String> export(@PathVariable long companyId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("from must be on or before to.");
		Integer journals = jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND status='POSTED' AND journal_date BETWEEN ? AND ?",
				Integer.class, companyId, from, to);
		if (journals != null && journals > MAX_EXPORT_JOURNALS)
			throw new IllegalArgumentException("More than " + MAX_EXPORT_JOURNALS
					+ " posted journals in this range. Export a shorter period.");
		record Row(long journalId, LocalDate date, String description, long accountId, String code, String name,
				String type, String systemKey, BigDecimal debit, BigDecimal credit) {
		}
		List<Row> rows = jdbc.query(
				"SELECT je.id,je.journal_date,je.description,a.id,a.code,a.name,a.account_type,a.system_key,l.debit,l.credit "
						+ "FROM journal_lines l JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id "
						+ "JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id "
						+ "WHERE l.company_id=? AND je.status='POSTED' AND je.journal_date BETWEEN ? AND ? "
						+ "ORDER BY je.journal_date,je.id,l.id",
				(rs, n) -> new Row(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getLong(4),
						rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9),
						rs.getBigDecimal(10)),
				companyId, from, to);
		Map<Long, Row> accounts = new LinkedHashMap<>();
		for (Row r : rows)
			accounts.putIfAbsent(r.accountId(), r);
		List<Row> sortedAccounts = new ArrayList<>(accounts.values());
		sortedAccounts.sort(Comparator.comparing(Row::code));
		Map<Long, String> names = TallyXmlWriter.ledgerNames(
				sortedAccounts.stream().map(r -> new AccountRef(r.accountId(), r.code(), r.name())).toList());
		List<LedgerOut> ledgers = sortedAccounts.stream()
				.map(r -> new LedgerOut(names.get(r.accountId()), TallyGroups.exportParent(r.type(), r.systemKey())))
				.toList();
		Map<Long, List<Row>> byJournal = new LinkedHashMap<>();
		for (Row r : rows)
			byJournal.computeIfAbsent(r.journalId(), k -> new ArrayList<>()).add(r);
		List<VoucherOut> vouchers = new ArrayList<>();
		byJournal.forEach((id, lines) -> vouchers.add(new VoucherOut(String.valueOf(id), lines.get(0).date(),
				lines.get(0).description(), lines.stream()
						.map(r -> new LineOut(names.get(r.accountId()), r.debit(), r.credit())).toList())));
		String xml = TallyXmlWriter.write(ledgers, vouchers);
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("tally-vouchers-" + from + "-to-" + to + ".xml").build()
								.toString())
				.body(xml);
	}

	private Context context(long companyId) {
		List<ExistingAccount> accounts = jdbc.query(
				"SELECT id,code,name,account_type FROM accounts WHERE company_id=? AND active=true",
				(rs, n) -> new ExistingAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				companyId);
		List<ExistingParty> parties = jdbc.query(
				"SELECT id,party_type,name,gstin FROM parties WHERE company_id=? AND active=true",
				(rs, n) -> new ExistingParty(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				companyId);
		Map<String, Long> keys = new HashMap<>();
		jdbc.query("SELECT idempotency_key,id FROM journal_entries WHERE company_id=? AND idempotency_key LIKE 'TALLY:%'",
				rs -> {
					keys.put(rs.getString(1), rs.getLong(2));
				}, companyId);
		List<String> state = jdbc.query("SELECT state_code FROM companies WHERE id=?", (rs, n) -> rs.getString(1),
				companyId);
		return new Context(accounts, parties, keys, state.isEmpty() ? null : state.get(0), systemAccount(companyId, "AR"),
				systemAccount(companyId, "AP"));
	}

	private Long systemAccount(long companyId, String key) {
		List<Long> ids = jdbc.query("SELECT id FROM accounts WHERE company_id=? AND system_key=? AND active=true",
				(rs, n) -> rs.getLong(1), companyId, key);
		return ids.isEmpty() ? null : ids.get(0);
	}

	private static byte[] read(MultipartFile file) {
		if (file == null || file.isEmpty())
			throw new IllegalArgumentException("The file is empty.");
		if (file.getSize() > TallyXmlParser.MAX_BYTES)
			throw new IllegalArgumentException("The Tally XML file is larger than 10 MB.");
		try {
			return file.getBytes();
		} catch (IOException e) {
			throw new IllegalArgumentException("The file could not be read.");
		}
	}

	/** "mapping" may arrive as a form field or as a JSON file part: {"Ledger name": 123 | "EXPENSE" | "VENDOR", ...}. */
	private Map<String, Object> overrides(MultipartHttpServletRequest request) {
		String json = request.getParameter("mapping");
		MultipartFile part = request.getFile("mapping");
		if ((json == null || json.isBlank()) && part != null && !part.isEmpty()) {
			if (part.getSize() > MAX_MAPPING_CHARS)
				throw new IllegalArgumentException("The mapping is too large.");
			try {
				json = new String(part.getBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalArgumentException("The mapping could not be read.");
			}
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
