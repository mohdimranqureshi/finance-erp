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

@RestController
@RequestMapping("/api/v1/companies/{companyId}/year-end")
public class YearEndController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public YearEndController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record CloseRequest(@NotNull Long periodId, @NotNull Long reservesAccountId) {
	}

	/** Drafts the entry that moves the period's profit or loss to reserves; it must be approved and posted like any journal. */
	@PostMapping("/closing-journal")
	@Transactional
	public Map<String, Object> closingJournal(@PathVariable long companyId, @Valid @RequestBody CloseRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER"));
		List<Map<String, Object>> periods = jdbc.queryForList(
				"SELECT start_date AS start,end_date AS \"end\",status FROM accounting_periods WHERE company_id=? AND id=? FOR UPDATE",
				companyId, req.periodId());
		if (periods.isEmpty())
			throw new IllegalArgumentException("Accounting period not found.");
		if (!"OPEN".equals(periods.get(0).get("status")))
			throw new IllegalArgumentException("The accounting period is already closed.");
		LocalDate start = ((java.sql.Date) periods.get(0).get("start")).toLocalDate();
		LocalDate end = ((java.sql.Date) periods.get(0).get("end")).toLocalDate();
		gst.requireAccount(companyId, req.reservesAccountId(), "EQUITY");
		Integer pending = jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND journal_date BETWEEN ? AND ? AND status IN ('DRAFT','SUBMITTED','APPROVED')",
				Integer.class, companyId, start, end);
		if (pending != null && pending > 0)
			throw new IllegalArgumentException(pending + " unposted journal(s) fall in this period. Post or discard them before closing.");
		List<Map<String, Object>> nets = jdbc.queryForList("""
				SELECT a.id AS account,SUM(l.credit-l.debit) AS net
				FROM accounts a
				JOIN journal_lines l ON l.company_id=a.company_id AND l.account_id=a.id
				JOIN journal_entries j ON j.company_id=l.company_id AND j.id=l.journal_entry_id
				WHERE a.company_id=? AND a.account_type IN ('INCOME','EXPENSE') AND j.status='POSTED'
				  AND NOT j.is_closing AND j.journal_date BETWEEN ? AND ?
				GROUP BY a.id HAVING SUM(l.credit-l.debit) <> 0 ORDER BY a.id
				""", companyId, start, end);
		if (nets.isEmpty())
			throw new IllegalArgumentException("There is no profit or loss activity to close in this period.");
		List<LedgerPoster.Line> lines = new ArrayList<>();
		BigDecimal profit = BigDecimal.ZERO;
		String label = "Year-end closing " + start + " to " + end;
		for (Map<String, Object> n : nets) {
			BigDecimal net = (BigDecimal) n.get("net");
			long account = ((Number) n.get("account")).longValue();
			profit = profit.add(net);
			lines.add(net.signum() > 0 ? new LedgerPoster.Line(account, label, net, BigDecimal.ZERO)
					: new LedgerPoster.Line(account, label, BigDecimal.ZERO, net.negate()));
		}
		if (profit.signum() > 0)
			lines.add(new LedgerPoster.Line(req.reservesAccountId(), label, BigDecimal.ZERO, profit));
		else if (profit.signum() < 0)
			lines.add(new LedgerPoster.Line(req.reservesAccountId(), label, profit.negate(), BigDecimal.ZERO));
		long journalId = poster.createDraftJournal(companyId, end, label, "YEAR_CLOSE:" + start + ":" + end, actor, lines);
		jdbc.update("UPDATE journal_entries SET is_closing=true WHERE company_id=? AND id=?", companyId, journalId);
		poster.audit(companyId, "CLOSING_JOURNAL_DRAFTED", "JOURNAL", journalId, label);
		return Map.of("journalId", journalId, "netProfit", profit, "periodStart", start, "periodEnd", end);
	}
}
