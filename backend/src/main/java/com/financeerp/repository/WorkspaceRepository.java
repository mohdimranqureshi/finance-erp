package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class WorkspaceRepository {
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

	// Mirrors JournalService.approve: OWNER/APPROVER, and neither creator nor submitter unless self-approval is on.
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

	public WorkspaceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Pass a non-null, already LIKE-escaped pattern to filter by name, legal name, GSTIN or label. */
	public List<Map<String, Object>> findClients(long userId, String likePattern) {
		List<Object> args = new ArrayList<>();
		args.add(userId);
		String filter = "";
		if (likePattern != null) {
			filter = "AND (c.name ILIKE ? OR c.legal_name ILIKE ? OR c.gstin ILIKE ? OR s.label ILIKE ?)";
			args.add(likePattern);
			args.add(likePattern);
			args.add(likePattern);
			args.add(likePattern);
		}
		return jdbc.queryForList(CLIENTS_SQL.formatted(filter), args.toArray());
	}

	public void upsertClientSettings(long userId, long companyId, boolean pinned, String label) {
		jdbc.update("""
				INSERT INTO workspace_client_settings(user_id,company_id,pinned,label) VALUES (?,?,?,?)
				ON CONFLICT (user_id,company_id) DO UPDATE SET pinned=EXCLUDED.pinned,label=EXCLUDED.label,updated_at=now()
				""", userId, companyId, pinned, label);
	}

	public Long countApprovable(long userId, Long companyId) {
		String companyFilter = companyId == null ? "" : " AND je.company_id=?";
		return jdbc.queryForObject("SELECT count(*) " + APPROVABLE_FROM + companyFilter, Long.class,
				approvableArgs(userId, companyId).toArray());
	}

	public List<Map<String, Object>> findApprovable(long userId, Long companyId, int limit, int offset) {
		String companyFilter = companyId == null ? "" : " AND je.company_id=?";
		List<Object> pageArgs = approvableArgs(userId, companyId);
		pageArgs.add(limit);
		pageArgs.add(offset);
		return jdbc.queryForList("""
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
	}

	private static List<Object> approvableArgs(long userId, Long companyId) {
		List<Object> args = new ArrayList<>(List.of(userId));
		if (companyId != null)
			args.add(companyId);
		return args;
	}
}
