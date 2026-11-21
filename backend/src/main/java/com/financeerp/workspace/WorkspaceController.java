package com.financeerp.workspace;

import com.financeerp.journal.JournalController;
import com.financeerp.security.CompanyAccess;
import com.financeerp.workspace.CloseStatusRules.ClientMetrics;
import com.financeerp.workspace.CloseStatusRules.CloseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Cross-client view for accountants who are members of many companies. Every figure is limited to the caller's
 * own memberships; approvals are delegated to {@link JournalController} so maker-checker rules and audit events
 * are identical to the per-company screen.
 */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
	private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);
	static final int MAX_PAGE = 200;
	static final int MAX_BULK = 100;
	private static final Set<String> SORTS = Set.of("status", "name", "approvals", "lastposted");

	// One statement for all of the caller's companies; each CTE is a single grouped pass over that table.
	private static final String CLIENTS_SQL = """
			WITH my AS (
			 SELECT c.id AS company_id,c.name,c.legal_name,c.gstin,c.allow_self_approval,m.role,m.user_id,
			  COALESCE(s.pinned,false) AS pinned,s.label
			 FROM company_memberships m
			 JOIN companies c ON c.id=m.company_id
			 LEFT JOIN workspace_client_settings s ON s.user_id=m.user_id AND s.company_id=m.company_id
			 WHERE m.user_id=? %s
			), j AS (
			 SELECT je.company_id,
			  count(*) FILTER (WHERE je.status='DRAFT') AS drafts,
			  count(*) FILTER (WHERE je.status='SUBMITTED') AS submitted,
			  count(*) FILTER (WHERE je.status='APPROVED') AS approved,
			  count(*) FILTER (WHERE je.status='SUBMITTED' AND my.role IN ('OWNER','APPROVER')
			   AND (my.allow_self_approval OR (je.submitted_by_user_id IS DISTINCT FROM my.user_id
			    AND je.created_by_user_id IS DISTINCT FROM my.user_id))) AS approvable,
			  count(*) FILTER (WHERE je.created_at < now() - interval '7 days') AS stale
			 FROM journal_entries je JOIN my ON my.company_id=je.company_id
			 WHERE je.status IN ('DRAFT','SUBMITTED','APPROVED')
			 GROUP BY je.company_id
			), b AS (
			 SELECT l.company_id,count(*) AS unmatched
			 FROM bank_statement_lines l JOIN my ON my.company_id=l.company_id
			 WHERE l.matched_payment_id IS NULL AND l.journal_entry_id IS NULL AND l.txn_date < current_date - 7
			 GROUP BY l.company_id
			), g AS (
			 SELECT DISTINCT ON (i.company_id) i.company_id,i.id,i.return_period,i.imported_at
			 FROM gstr2b_imports i JOIN my ON my.company_id=i.company_id
			 ORDER BY i.company_id,substr(i.return_period,3,4) DESC,substr(i.return_period,1,2) DESC
			), gr AS (
			 SELECT r.company_id,
			  COUNT(*) FILTER (WHERE r.status<>'MATCHED' AND r.resolution IS NULL) AS open_exceptions,
			  COALESCE(SUM(CASE
			   WHEN r.bill_id IS NULL THEN 0
			   WHEN r.status IN ('MISSING_IN_2B','ITC_NOT_AVAILABLE') THEN b.igst_amount+b.cgst_amount+b.sgst_amount
			   WHEN r.status='MISMATCH' THEN GREATEST(0,(b.igst_amount+b.cgst_amount+b.sgst_amount)-(l.igst_amount+l.cgst_amount+l.sgst_amount))
			   ELSE 0 END),0) AS itc_at_risk
			 FROM g
			 JOIN gstr2b_recon_items r ON r.company_id=g.company_id AND r.import_id=g.id
			 LEFT JOIN gstr2b_lines l ON l.company_id=r.company_id AND l.id=r.line_id
			 LEFT JOIN purchase_bills b ON b.company_id=r.company_id AND b.id=r.bill_id
			 GROUP BY r.company_id
			), ar AS (
			 SELECT x.company_id,count(*) AS cnt,COALESCE(sum(x.outstanding),0) AS amount FROM (
			  SELECT i.company_id,i.total_amount
			   - COALESCE((SELECT SUM(p.amount+p.tds_amount) FROM payments p WHERE p.company_id=i.company_id AND p.sales_invoice_id=i.id),0)
			   - COALESCE((SELECT SUM(n.total_amount) FROM adjustment_notes n WHERE n.company_id=i.company_id AND n.sales_invoice_id=i.id),0) AS outstanding
			  FROM sales_invoices i JOIN my ON my.company_id=i.company_id
			  WHERE i.status='ISSUED' AND COALESCE(i.due_date,i.invoice_date) < current_date - 60
			 ) x WHERE x.outstanding > 0
			 GROUP BY x.company_id
			), p AS (
			 SELECT ap.company_id,count(*) AS cnt
			 FROM accounting_periods ap JOIN my ON my.company_id=ap.company_id
			 WHERE ap.status='OPEN' AND ap.end_date < current_date - 30
			 GROUP BY ap.company_id
			)
			SELECT my.company_id,my.name,my.legal_name,my.gstin,my.role,my.pinned,my.label,
			 COALESCE(j.drafts,0) AS drafts,COALESCE(j.submitted,0) AS submitted,COALESCE(j.approved,0) AS approved,
			 COALESCE(j.approvable,0) AS approvable,COALESCE(j.stale,0) AS stale,COALESCE(b.unmatched,0) AS unmatched,
			 g.return_period,g.imported_at,COALESCE(gr.open_exceptions,0) AS open_exceptions,
			 COALESCE(gr.itc_at_risk,0) AS itc_at_risk,COALESCE(ar.cnt,0) AS overdue_count,
			 COALESCE(ar.amount,0) AS overdue_amount,COALESCE(p.cnt,0) AS overdue_periods,lp.last_posted
			FROM my
			LEFT JOIN j ON j.company_id=my.company_id
			LEFT JOIN b ON b.company_id=my.company_id
			LEFT JOIN g ON g.company_id=my.company_id
			LEFT JOIN gr ON gr.company_id=my.company_id
			LEFT JOIN ar ON ar.company_id=my.company_id
			LEFT JOIN p ON p.company_id=my.company_id
			LEFT JOIN LATERAL (SELECT max(x.journal_date) AS last_posted FROM journal_entries x
			 WHERE x.company_id=my.company_id AND x.posted_at IS NOT NULL) lp ON true
			""";

	// Mirrors JournalController.approve: OWNER/APPROVER, and neither creator nor submitter unless self-approval is on.
	private static final String APPROVABLE_FROM = """
			FROM journal_entries je
			JOIN company_memberships m ON m.company_id=je.company_id AND m.user_id=? AND m.role IN ('OWNER','APPROVER')
			JOIN companies c ON c.id=je.company_id
			LEFT JOIN app_users su ON su.id=je.submitted_by_user_id
			WHERE je.status='SUBMITTED'
			 AND (c.allow_self_approval OR (je.submitted_by_user_id IS DISTINCT FROM m.user_id
			  AND je.created_by_user_id IS DISTINCT FROM m.user_id))
			""";

	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final JournalController journals;

	public WorkspaceController(JdbcTemplate jdbc, CompanyAccess access, JournalController journals) {
		this.jdbc = jdbc;
		this.access = access;
		this.journals = journals;
	}

	public record BulkItem(@NotNull Long companyId, @NotNull Long journalId) {
	}

	public record BulkApproveRequest(@NotEmpty @Size(max = MAX_BULK) List<@Valid @NotNull BulkItem> items) {
	}

	public record BulkResult(long companyId, long journalId, boolean ok, String error) {
	}

	public record ClientSettingsRequest(Boolean pinned, @Size(max = 80) String label) {
	}

	@GetMapping("/clients")
	public List<Map<String, Object>> clients(@RequestParam(required = false) String search,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "status") String sort) {
		String wantedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
		if (wantedStatus != null && !Set.of(CloseStatusRules.CLEAN, CloseStatusRules.ATTENTION, CloseStatusRules.BLOCKED)
				.contains(wantedStatus))
			throw new IllegalArgumentException("status must be CLEAN, ATTENTION or BLOCKED.");
		String sortKey = sort.trim().toLowerCase(Locale.ROOT);
		if (!SORTS.contains(sortKey))
			throw new IllegalArgumentException("sort must be one of status, name, approvals, lastPosted.");
		long userId = access.currentUserId();
		List<Object> args = new ArrayList<>();
		args.add(userId);
		String filter = "";
		if (search != null && !search.isBlank()) {
			if (search.length() > 100)
				throw new IllegalArgumentException("search must be 100 characters or fewer.");
			String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			filter = "AND (c.name ILIKE ? OR c.legal_name ILIKE ? OR c.gstin ILIKE ? OR s.label ILIKE ?)";
			Collections.addAll(args, like, like, like, like);
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : jdbc.queryForList(CLIENTS_SQL.formatted(filter), args.toArray())) {
			ClientMetrics m = new ClientMetrics(r.get("gstin") != null, lng(r, "drafts"), lng(r, "submitted"),
					lng(r, "approved"), lng(r, "approvable"), lng(r, "stale"), lng(r, "unmatched"),
					(String) r.get("return_period"), lng(r, "open_exceptions"), dec(r, "itc_at_risk"),
					lng(r, "overdue_count"), dec(r, "overdue_amount"), lng(r, "overdue_periods"),
					date(r, "last_posted"));
			CloseStatus close = CloseStatusRules.evaluate(m);
			if (wantedStatus != null && !wantedStatus.equals(close.status()))
				continue;
			out.add(client(r, m, close));
		}
		out.sort(comparator(sortKey));
		return out;
	}

	@PutMapping("/clients/{companyId}/settings")
	public Map<String, Object> updateSettings(@PathVariable long companyId,
			@Valid @RequestBody ClientSettingsRequest req) {
		long userId = access.requireMembership(companyId);
		String label = req.label() == null || req.label().isBlank() ? null : req.label().trim();
		boolean pinned = Boolean.TRUE.equals(req.pinned());
		jdbc.update("""
				INSERT INTO workspace_client_settings(user_id,company_id,pinned,label) VALUES (?,?,?,?)
				ON CONFLICT (user_id,company_id) DO UPDATE SET pinned=EXCLUDED.pinned,label=EXCLUDED.label,updated_at=now()
				""", userId, companyId, pinned, label);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("companyId", companyId);
		out.put("pinned", pinned);
		out.put("label", label);
		return out;
	}

	@GetMapping("/approvals")
	public Map<String, Object> approvals(@RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) Long companyId) {
		if (limit < 1 || limit > MAX_PAGE)
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE + ".");
		if (offset < 0)
			throw new IllegalArgumentException("offset cannot be negative.");
		long userId = access.currentUserId();
		String companyFilter = companyId == null ? "" : " AND je.company_id=?";
		List<Object> args = new ArrayList<>(List.of(userId));
		if (companyId != null)
			args.add(companyId);
		Long total = jdbc.queryForObject("SELECT count(*) " + APPROVABLE_FROM + companyFilter, Long.class,
				args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(limit);
		pageArgs.add(offset);
		List<Map<String, Object>> items = jdbc.queryForList("""
				SELECT je.company_id AS "companyId",c.name AS "companyName",je.id AS "journalId",
				 je.journal_date AS "journalDate",je.description,
				 (SELECT COALESCE(sum(jl.debit),0) FROM journal_lines jl
				  WHERE jl.company_id=je.company_id AND jl.journal_entry_id=je.id) AS "totalDebit",
				 je.submitted_by_user_id AS "submittedByUserId",su.display_name AS "submittedBy",
				 su.email AS "submittedByEmail",je.submitted_at AS "submittedAt",
				 GREATEST(0,current_date - COALESCE(je.submitted_at,je.created_at)::date) AS "ageDays",
				 EXISTS(SELECT 1 FROM accounting_periods ap WHERE ap.company_id=je.company_id AND ap.status='OPEN'
				  AND ap.start_date<=je.journal_date AND ap.end_date>=je.journal_date) AS "periodOpen"
				""" + APPROVABLE_FROM + companyFilter + """

				ORDER BY je.submitted_at ASC NULLS LAST,je.company_id,je.id
				LIMIT ? OFFSET ?
				""", pageArgs.toArray());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("total", total == null ? 0 : total);
		out.put("limit", limit);
		out.put("offset", offset);
		return out;
	}

	/**
	 * Approves each item independently through {@link JournalController#approve}. That method is
	 * {@code @Transactional} on a proxied bean and this one is not, so every item commits or rolls back on its own.
	 * Journals are only approved here, never posted.
	 */
	@PostMapping("/approvals/bulk")
	public Map<String, Object> bulkApprove(@Valid @RequestBody BulkApproveRequest req) {
		List<BulkResult> results = new ArrayList<>();
		int approved = 0;
		for (BulkItem item : req.items()) {
			long companyId = item.companyId(), journalId = item.journalId();
			try {
				journals.approve(companyId, journalId);
				results.add(new BulkResult(companyId, journalId, true, null));
				approved++;
			} catch (IllegalArgumentException | AccessDeniedException ex) {
				results.add(new BulkResult(companyId, journalId, false, ex.getMessage()));
			} catch (RuntimeException ex) {
				log.warn("Bulk approval failed for company {} journal {}", companyId, journalId, ex);
				results.add(new BulkResult(companyId, journalId, false,
						"Could not approve this journal because of an unexpected error. Try it individually."));
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("approved", approved);
		out.put("failed", results.size() - approved);
		out.put("results", results);
		return out;
	}

	private static Map<String, Object> client(Map<String, Object> r, ClientMetrics m, CloseStatus close) {
		Map<String, Object> gstr2b = null;
		if (m.gstr2bPeriod() != null) {
			gstr2b = new LinkedHashMap<>();
			gstr2b.put("returnPeriod", m.gstr2bPeriod());
			gstr2b.put("importedAt", r.get("imported_at"));
			gstr2b.put("openExceptions", m.gstr2bOpenExceptions());
			gstr2b.put("itcAtRisk", m.itcAtRisk());
		}
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("draftJournals", m.draftJournals());
		metrics.put("submittedJournals", m.submittedJournals());
		metrics.put("approvedNotPosted", m.approvedNotPosted());
		metrics.put("approvableByMe", m.approvableByMe());
		metrics.put("staleJournals", m.staleJournals());
		metrics.put("unmatchedBankLines", m.unmatchedBankLines());
		metrics.put("gstr2b", gstr2b);
		metrics.put("overdueReceivables", Map.of("count", m.overdueReceivables(), "amount", m.overdueReceivableAmount()));
		metrics.put("overdueOpenPeriods", m.overdueOpenPeriods());
		metrics.put("lastPostedJournalDate", m.lastPostedJournalDate());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("companyId", lng(r, "company_id"));
		out.put("name", r.get("name"));
		out.put("legalName", r.get("legal_name"));
		out.put("gstin", r.get("gstin"));
		out.put("role", r.get("role"));
		out.put("pinned", Boolean.TRUE.equals(r.get("pinned")));
		out.put("label", r.get("label"));
		out.put("metrics", metrics);
		out.put("closeStatus", close.status());
		out.put("closeReasons", close.reasons());
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Comparator<Map<String, Object>> comparator(String sort) {
		Comparator<Map<String, Object>> pinnedFirst = Comparator.comparing(c -> !(Boolean) c.get("pinned"));
		Comparator<Map<String, Object>> byName = Comparator
				.comparing(c -> String.valueOf(c.get("name")), String.CASE_INSENSITIVE_ORDER);
		Comparator<Map<String, Object>> primary = switch (sort) {
		case "name" -> byName;
		case "approvals" -> Comparator.comparingLong(
				(Map<String, Object> c) -> -(Long) ((Map<String, Object>) c.get("metrics")).get("approvableByMe"));
		// Oldest (or never) posted first: those are the clients falling behind.
		case "lastposted" -> Comparator.comparing(
				(Map<String, Object> c) -> (LocalDate) ((Map<String, Object>) c.get("metrics")).get("lastPostedJournalDate"),
				Comparator.nullsFirst(Comparator.naturalOrder()));
		default -> Comparator.comparingInt(
				(Map<String, Object> c) -> -CloseStatusRules.severity((String) c.get("closeStatus")));
		};
		return pinnedFirst.thenComparing(primary).thenComparing(byName);
	}

	private static long lng(Map<String, Object> r, String key) {
		Object v = r.get(key);
		return v == null ? 0 : ((Number) v).longValue();
	}

	private static BigDecimal dec(Map<String, Object> r, String key) {
		Object v = r.get(key);
		return v == null ? BigDecimal.ZERO : v instanceof BigDecimal d ? d : new BigDecimal(v.toString());
	}

	private static LocalDate date(Map<String, Object> r, String key) {
		Object v = r.get(key);
		return v instanceof java.sql.Date d ? d.toLocalDate() : v instanceof LocalDate l ? l : null;
	}
}
