package com.financeerp.service;

import com.financeerp.domain.LedgerLine;
import com.financeerp.repository.AuditRepository;
import com.financeerp.repository.LedgerRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Creates DRAFT journals only; approval and posting stay in the maker-checker workflow. */
@Service
public class LedgerPoster {
	private final LedgerRepository ledger;
	private final AuditRepository audit;

	public LedgerPoster(LedgerRepository ledger, AuditRepository audit) {
		this.ledger = ledger;
		this.audit = audit;
	}

	public long systemAccount(long companyId, String systemKey) {
		List<Long> ids = ledger.findActiveSystemAccountIds(companyId, systemKey);
		if (ids.isEmpty())
			throw new IllegalArgumentException(
					"System account " + systemKey + " is missing. Run the India chart-of-accounts setup first.");
		return ids.get(0);
	}

	public void requireOpenPeriod(long companyId, LocalDate date) {
		if (ledger.countOpenPeriodsContaining(companyId, date) == 0)
			throw new IllegalArgumentException("No open accounting period contains " + date + ".");
	}

	public long createDraftJournal(long companyId, LocalDate date, String description, String idempotencyKey,
			long actorId, List<LedgerLine> lines) {
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		for (LedgerLine l : lines) {
			debits = debits.add(l.debit());
			credits = credits.add(l.credit());
		}
		if (lines.size() < 2 || debits.compareTo(credits) != 0)
			throw new IllegalStateException("Generated journal is not balanced.");
		long id = ledger.insertDraftJournal(companyId, date, description, idempotencyKey, actorId);
		for (LedgerLine l : lines)
			ledger.insertJournalLine(companyId, id, l.accountId(), l.description(), l.debit(), l.credit());
		return id;
	}

	public void audit(long companyId, String action, String entityType, long entityId, String detail) {
		audit.insertCompanyEvent(companyId, SecurityContextHolder.getContext().getAuthentication().getName(), action,
				entityType, entityId, detail);
	}
}