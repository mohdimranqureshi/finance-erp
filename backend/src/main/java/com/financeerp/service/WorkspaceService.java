package com.financeerp.service;

import com.financeerp.domain.CloseStatusRules;
import com.financeerp.domain.CloseStatusRules.ClientMetrics;
import com.financeerp.domain.CloseStatusRules.CloseStatus;
import com.financeerp.dto.WorkspaceDto;
import com.financeerp.repository.WorkspaceRepository;
import com.financeerp.security.CompanyAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Cross-client view for accountants who are members of many companies. Every figure is limited to the caller's
 * own memberships; approvals are delegated to {@link JournalService} so maker-checker rules and audit events
 * are identical to the per-company screen. This class is deliberately not transactional.
 */
@Service
public class WorkspaceService {
	private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
	static final int MAX_PAGE = 200;
	private static final Set<String> SORTS = Set.of("status", "name", "approvals", "lastposted");

	private final WorkspaceRepository workspace;
	private final CompanyAccess access;
	private final JournalService journals;

	public WorkspaceService(WorkspaceRepository workspace, CompanyAccess access, JournalService journals) {
		this.workspace = workspace;
		this.access = access;
		this.journals = journals;
	}

	public List<Map<String, Object>> clients(String search, String status, String sort) {
		String wantedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
		if (wantedStatus != null && !Set.of(CloseStatusRules.CLEAN, CloseStatusRules.ATTENTION, CloseStatusRules.BLOCKED)
				.contains(wantedStatus))
			throw new IllegalArgumentException("status must be CLEAN, ATTENTION or BLOCKED.");
		String sortKey = sort.trim().toLowerCase(Locale.ROOT);
		if (!SORTS.contains(sortKey))
			throw new IllegalArgumentException("sort must be one of status, name, approvals, lastPosted.");
		long userId = access.currentUserId();
		String like = null;
		if (search != null && !search.isBlank()) {
			if (search.length() > 100)
				throw new IllegalArgumentException("search must be 100 characters or fewer.");
			like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : workspace.findClients(userId, like)) {
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

	public Map<String, Object> updateSettings(long companyId, WorkspaceDto.ClientSettingsRequest req) {
		long userId = access.requireMembership(companyId);
		String label = req.label() == null || req.label().isBlank() ? null : req.label().trim();
		boolean pinned = Boolean.TRUE.equals(req.pinned());
		workspace.upsertClientSettings(userId, companyId, pinned, label);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("companyId", companyId);
		out.put("pinned", pinned);
		out.put("label", label);
		return out;
	}

	public Map<String, Object> approvals(int limit, int offset, Long companyId) {
		if (limit < 1 || limit > MAX_PAGE)
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE + ".");
		if (offset < 0)
			throw new IllegalArgumentException("offset cannot be negative.");
		long userId = access.currentUserId();
		Long total = workspace.countApprovable(userId, companyId);
		List<Map<String, Object>> items = workspace.findApprovable(userId, companyId, limit, offset);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("total", total == null ? 0 : total);
		out.put("limit", limit);
		out.put("offset", offset);
		return out;
	}

	/**
	 * Approves each item independently through {@link JournalService#approve}. That method is
	 * {@code @Transactional} on a proxied bean and this one is not, so every item commits or rolls back on its own.
	 * Journals are only approved here, never posted.
	 */
	public Map<String, Object> bulkApprove(WorkspaceDto.BulkApproveRequest req) {
		List<WorkspaceDto.BulkResult> results = new ArrayList<>();
		int approved = 0;
		for (WorkspaceDto.BulkItem item : req.items()) {
			long companyId = item.companyId(), journalId = item.journalId();
			try {
				journals.approve(companyId, journalId);
				results.add(new WorkspaceDto.BulkResult(companyId, journalId, true, null));
				approved++;
			} catch (IllegalArgumentException | AccessDeniedException ex) {
				results.add(new WorkspaceDto.BulkResult(companyId, journalId, false, ex.getMessage()));
			} catch (RuntimeException ex) {
				log.warn("Bulk approval failed for company {} journal {}", companyId, journalId, ex);
				results.add(new WorkspaceDto.BulkResult(companyId, journalId, false,
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
