package com.financeerp.service;

import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.YearEndDto.CloseRequest;
import com.financeerp.repository.YearEndRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class YearEndService {
	private final YearEndRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public YearEndService(YearEndRepository repository, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	/** Drafts the entry that moves the period's profit or loss to reserves; it must be approved and posted like any journal. */
	@Transactional
	public Map<String, Object> closingJournal(long companyId, CloseRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER"));
		List<Map<String, Object>> periods = repository.lockPeriod(companyId, req.periodId());
		if (periods.isEmpty())
			throw new IllegalArgumentException("Accounting period not found.");
		if (!"OPEN".equals(periods.get(0).get("status")))
			throw new IllegalArgumentException("The accounting period is already closed.");
		LocalDate start = ((java.sql.Date) periods.get(0).get("start")).toLocalDate();
		LocalDate end = ((java.sql.Date) periods.get(0).get("end")).toLocalDate();
		gst.requireAccount(companyId, req.reservesAccountId(), "EQUITY");
		int pending = repository.countUnpostedJournals(companyId, start, end);
		if (pending > 0)
			throw new IllegalArgumentException(pending + " unposted journal(s) fall in this period. Post or discard them before closing.");
		List<Map<String, Object>> nets = repository.findProfitLossNets(companyId, start, end);
		if (nets.isEmpty())
			throw new IllegalArgumentException("There is no profit or loss activity to close in this period.");
		List<LedgerLine> lines = new ArrayList<>();
		BigDecimal profit = BigDecimal.ZERO;
		String label = "Year-end closing " + start + " to " + end;
		for (Map<String, Object> n : nets) {
			BigDecimal net = (BigDecimal) n.get("net");
			long account = ((Number) n.get("account")).longValue();
			profit = profit.add(net);
			lines.add(net.signum() > 0 ? new LedgerLine(account, label, net, BigDecimal.ZERO)
					: new LedgerLine(account, label, BigDecimal.ZERO, net.negate()));
		}
		if (profit.signum() > 0)
			lines.add(new LedgerLine(req.reservesAccountId(), label, BigDecimal.ZERO, profit));
		else if (profit.signum() < 0)
			lines.add(new LedgerLine(req.reservesAccountId(), label, profit.negate(), BigDecimal.ZERO));
		long journalId = poster.createDraftJournal(companyId, end, label, "YEAR_CLOSE:" + start + ":" + end, actor, lines);
		repository.markClosingJournal(companyId, journalId);
		poster.audit(companyId, "CLOSING_JOURNAL_DRAFTED", "JOURNAL", journalId, label);
		return Map.of("journalId", journalId, "netProfit", profit, "periodStart", start, "periodEnd", end);
	}
}
