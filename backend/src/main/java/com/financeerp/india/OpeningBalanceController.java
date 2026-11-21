package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Opening balances: one batch per company producing a single DRAFT journal (GL lines, AR/AP from party items, the
 * difference to OPENING_BALANCE equity). A replacement batch is accepted only after the previous batch's journal has
 * been reversed and the reversal posted.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/opening-balances")
public class OpeningBalanceController {
	/** True when journal alias j has been undone: legacy REVERSED status or a posted reversal journal. */
	static final String JOURNAL_REVERSED = """
			(j.status='REVERSED' OR EXISTS (SELECT 1 FROM journal_entries r
			 WHERE r.company_id=j.company_id AND r.id=j.reversed_by_journal_id AND r.status='POSTED'))""";
	/** Outstanding balance of opening item alias o (receipts include TDS deducted by the customer). */
	static final String ITEM_OUTSTANDING = """
			o.amount
			 - COALESCE((SELECT SUM(x.amount+x.tds_amount) FROM payments x WHERE x.company_id=o.company_id AND x.opening_item_id=o.id),0)
			""";
	/** Joins opening item alias o to its batch b and journal j. */
	static final String ITEM_JOIN = """
			opening_balance_items o
			 JOIN opening_balance_batches b ON b.company_id=o.company_id AND b.id=o.batch_id
			 JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
			""";
	/** Opening items that can still be settled: batch active and its journal not reversed. */
	static final String ITEM_SETTLEABLE = "b.status='ACTIVE' AND NOT " + JOURNAL_REVERSED;

	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public OpeningBalanceController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record LineRequest(Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
	}

	public record ReceivableRequest(Long customerId, String documentNumber, LocalDate documentDate, LocalDate dueDate,
			BigDecimal amount) {
	}

	public record PayableRequest(Long vendorId, String documentNumber, LocalDate documentDate, LocalDate dueDate,
			BigDecimal amount) {
	}

	public record CreateRequest(@NotNull LocalDate asOfDate, List<@Valid LineRequest> lines,
			List<@Valid ReceivableRequest> receivables, List<@Valid PayableRequest> payables) {
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		// Serialise batch creation per company.
		jdbc.queryForObject("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, companyId);
		List<Map<String, Object>> active = jdbc.queryForList("SELECT b.id,b.journal_entry_id AS \"journalId\",j.status,"
				+ JOURNAL_REVERSED + " AS reversed FROM opening_balance_batches b JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id"
				+ " WHERE b.company_id=? AND b.status='ACTIVE'", companyId);
		for (Map<String, Object> prev : active) {
			if (!Boolean.TRUE.equals(prev.get("reversed")))
				throw new IllegalArgumentException("Opening balances were already entered (batch #" + prev.get("id")
						+ ", journal #" + prev.get("journalId") + " is " + prev.get("status")
						+ "). To replace them, post the journal, reverse it and post the reversal first.");
			jdbc.update("UPDATE opening_balance_batches SET status='SUPERSEDED',superseded_at=now() WHERE company_id=? AND id=?",
					companyId, prev.get("id"));
		}

		OpeningBalancePlanner.Accounts accounts = new OpeningBalancePlanner.Accounts(poster.systemAccount(companyId, "AR"),
				poster.systemAccount(companyId, "AP"), poster.systemAccount(companyId, "OPENING_BALANCE"));
		List<OpeningBalancePlanner.GlLine> gl = new ArrayList<>();
		for (LineRequest l : nn(req.lines()))
			gl.add(l == null ? null : new OpeningBalancePlanner.GlLine(l.accountId(), l.debit(), l.credit(), l.memo()));
		List<OpeningBalancePlanner.PartyItem> rec = new ArrayList<>();
		for (ReceivableRequest r : nn(req.receivables()))
			rec.add(r == null ? null
					: new OpeningBalancePlanner.PartyItem(r.customerId(), r.documentNumber(), r.documentDate(), r.dueDate(), r.amount()));
		List<OpeningBalancePlanner.PartyItem> pay = new ArrayList<>();
		for (PayableRequest p : nn(req.payables()))
			pay.add(p == null ? null
					: new OpeningBalancePlanner.PartyItem(p.vendorId(), p.documentNumber(), p.documentDate(), p.dueDate(), p.amount()));
		OpeningBalancePlanner.Plan plan = OpeningBalancePlanner.plan(req.asOfDate(), gl, rec, pay, accounts);

		for (Long accountId : new LinkedHashSet<>(gl.stream().map(OpeningBalancePlanner.GlLine::accountId).toList())) {
			Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true",
					Integer.class, companyId, accountId);
			if (n == null || n == 0)
				throw new IllegalArgumentException("Account " + accountId + " is inactive or does not belong to this company.");
		}
		for (Long id : new LinkedHashSet<>(plan.receivables().stream().map(OpeningBalancePlanner.PartyItem::partyId).toList()))
			gst.party(companyId, id, "CUSTOMER");
		for (Long id : new LinkedHashSet<>(plan.payables().stream().map(OpeningBalancePlanner.PartyItem::partyId).toList()))
			gst.party(companyId, id, "VENDOR");
		poster.requireOpenPeriod(companyId, req.asOfDate());

		long journalId = poster.createDraftJournal(companyId, req.asOfDate(), "Opening balances as of " + req.asOfDate(),
				"OPENING:" + UUID.randomUUID(), actor, plan.lines());
		Long batchId = jdbc.queryForObject(
				"INSERT INTO opening_balance_batches(company_id,as_of_date,receivable_total,payable_total,equity_adjustment,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.asOfDate(), plan.receivableTotal(), plan.payableTotal(), plan.equityAdjustment(),
				journalId, actor);
		insertItems(companyId, batchId, "RECEIVABLE", plan.receivables());
		insertItems(companyId, batchId, "PAYABLE", plan.payables());
		poster.audit(companyId, "OPENING_BALANCES_CREATED", "OPENING_BALANCE_BATCH", batchId,
				"as of " + req.asOfDate() + "; journal " + journalId + "; " + gl.size() + " GL line(s), "
						+ plan.receivables().size() + " receivable(s) " + plan.receivableTotal().toPlainString() + ", "
						+ plan.payables().size() + " payable(s) " + plan.payableTotal().toPlainString()
						+ ", equity adjustment " + plan.equityAdjustment().toPlainString());
		return get(companyId);
	}

	/** The current batch (active, else the latest superseded one), its journal status and party items. */
	@GetMapping
	public Map<String, Object> get(@PathVariable long companyId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> batches = jdbc.queryForList("""
				SELECT b.id,b.as_of_date AS "asOfDate",b.status,b.receivable_total AS "receivableTotal",
				 b.payable_total AS "payableTotal",b.equity_adjustment AS "equityAdjustment",
				 b.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",
				 j.reversed_by_journal_id AS "reversedByJournalId",""" + JOURNAL_REVERSED + """
				 AS "journalReversed",b.created_by_user_id AS "createdByUserId",b.created_at AS "createdAt",
				 b.superseded_at AS "supersededAt"
				FROM opening_balance_batches b JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
				WHERE b.company_id=? ORDER BY (b.status='ACTIVE') DESC,b.id DESC LIMIT 1
				""", companyId);
		Map<String, Object> out = new LinkedHashMap<>();
		if (batches.isEmpty()) {
			out.put("batch", null);
			out.put("receivables", List.of());
			out.put("payables", List.of());
			return out;
		}
		Map<String, Object> batch = batches.get(0);
		out.put("batch", batch);
		out.put("receivables", items(companyId, ((Number) batch.get("id")).longValue(), "RECEIVABLE"));
		out.put("payables", items(companyId, ((Number) batch.get("id")).longValue(), "PAYABLE"));
		return out;
	}

	private List<Map<String, Object>> items(long companyId, long batchId, String type) {
		return jdbc.queryForList("SELECT o.id,o.party_id AS \"partyId\",p.name AS party,o.document_number AS \"documentNumber\","
				+ "o.document_date AS \"documentDate\",o.due_date AS \"dueDate\",o.amount," + ITEM_OUTSTANDING
				+ " AS outstanding FROM opening_balance_items o JOIN parties p ON p.company_id=o.company_id AND p.id=o.party_id"
				+ " WHERE o.company_id=? AND o.batch_id=? AND o.item_type=? ORDER BY o.document_date,o.id",
				companyId, batchId, type);
	}

	private void insertItems(long companyId, long batchId, String type, List<OpeningBalancePlanner.PartyItem> items) {
		for (OpeningBalancePlanner.PartyItem i : items)
			jdbc.update(
					"INSERT INTO opening_balance_items(company_id,batch_id,item_type,party_id,document_number,document_date,due_date,amount) VALUES (?,?,?,?,?,?,?,?)",
					companyId, batchId, type, i.partyId(), i.documentNumber(), i.documentDate(), i.dueDate(), i.amount());
	}

	private static <T> List<T> nn(List<T> list) {
		return list == null ? List.of() : list;
	}
}
