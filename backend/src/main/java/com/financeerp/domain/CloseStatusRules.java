package com.financeerp.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure close-readiness rules for one client; no database or clock access so they are easy to unit test. */
public final class CloseStatusRules {
	public static final String CLEAN = "CLEAN";
	public static final String ATTENTION = "ATTENTION";
	public static final String BLOCKED = "BLOCKED";

	private CloseStatusRules() {
	}

	/** Per-client figures gathered by the workspace query. Null gstr2bPeriod means no import yet. */
	public record ClientMetrics(boolean gstRegistered, long draftJournals, long submittedJournals,
			long approvedNotPosted, long approvableByMe, long staleJournals, long unmatchedBankLines,
			String gstr2bPeriod, long gstr2bOpenExceptions, BigDecimal itcAtRisk, long overdueReceivables,
			BigDecimal overdueReceivableAmount, long overdueOpenPeriods, LocalDate lastPostedJournalDate) {
	}

	public record Reason(String level, String code, String message) {
	}

	public record CloseStatus(String status, List<Reason> reasons) {
	}

	public static CloseStatus evaluate(ClientMetrics m) {
		List<Reason> blocked = new ArrayList<>();
		List<Reason> attention = new ArrayList<>();
		if (m.overdueOpenPeriods() > 0)
			blocked.add(new Reason(BLOCKED, "PERIOD_NOT_CLOSED", plural(m.overdueOpenPeriods(), "accounting period")
					+ " still open more than 30 days after the period end date"));
		if (m.staleJournals() > 0)
			blocked.add(new Reason(BLOCKED, "STALE_JOURNALS", plural(m.staleJournals(), "journal")
					+ " stuck in draft, submitted or approved for more than 7 days"));
		BigDecimal itc = m.itcAtRisk() == null ? BigDecimal.ZERO : m.itcAtRisk();
		if (m.gstr2bPeriod() != null && m.gstr2bOpenExceptions() > 0 && itc.signum() > 0)
			blocked.add(new Reason(BLOCKED, "GSTR2B_ITC_AT_RISK", "GSTR-2B " + m.gstr2bPeriod() + ": INR " + amount(itc)
					+ " ITC at risk with " + plural(m.gstr2bOpenExceptions(), "open exception")));
		else if (m.gstr2bPeriod() != null && m.gstr2bOpenExceptions() > 0)
			attention.add(new Reason(ATTENTION, "GSTR2B_EXCEPTIONS", "GSTR-2B " + m.gstr2bPeriod() + ": "
					+ plural(m.gstr2bOpenExceptions(), "open exception") + " to resolve"));
		if (m.submittedJournals() > 0)
			attention.add(new Reason(ATTENTION, "AWAITING_APPROVAL", plural(m.submittedJournals(), "journal")
					+ " awaiting approval" + (m.approvableByMe() > 0 ? " (" + m.approvableByMe() + " by you)" : "")));
		if (m.approvedNotPosted() > 0)
			attention.add(new Reason(ATTENTION, "APPROVED_NOT_POSTED",
					plural(m.approvedNotPosted(), "approved journal") + " not yet posted"));
		if (m.draftJournals() > 0)
			attention.add(new Reason(ATTENTION, "DRAFT_JOURNALS",
					plural(m.draftJournals(), "draft journal") + " not yet submitted"));
		if (m.unmatchedBankLines() > 0)
			attention.add(new Reason(ATTENTION, "UNMATCHED_BANK",
					plural(m.unmatchedBankLines(), "bank statement line") + " unmatched for more than 7 days"));
		if (m.overdueReceivables() > 0)
			attention.add(new Reason(ATTENTION, "OVERDUE_RECEIVABLES", plural(m.overdueReceivables(), "receivable")
					+ " overdue by more than 60 days (INR " + amount(m.overdueReceivableAmount()) + ")"));
		if (m.gstRegistered() && m.gstr2bPeriod() == null)
			attention.add(new Reason(ATTENTION, "NO_GSTR2B", "No GSTR-2B has been imported yet"));
		List<Reason> reasons = new ArrayList<>(blocked);
		reasons.addAll(attention);
		String status = !blocked.isEmpty() ? BLOCKED : !attention.isEmpty() ? ATTENTION : CLEAN;
		return new CloseStatus(status, List.copyOf(reasons));
	}

	/** Higher is worse; used for sorting. */
	public static int severity(String status) {
		return switch (status) {
		case BLOCKED -> 2;
		case ATTENTION -> 1;
		default -> 0;
		};
	}

	private static String plural(long n, String noun) {
		return n + " " + noun + (n == 1 ? "" : "s");
	}

	private static String amount(BigDecimal value) {
		return String.format(Locale.ROOT, "%,.2f",
				(value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP));
	}
}
