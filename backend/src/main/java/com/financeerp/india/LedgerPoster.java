package com.financeerp.india;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Creates DRAFT journals only; approval and posting stay in the maker-checker workflow. */
@Component
public class LedgerPoster {
	private final JdbcTemplate jdbc;

	public LedgerPoster(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Line(long accountId, String description, BigDecimal debit, BigDecimal credit) {
	}

	public long systemAccount(long companyId, String systemKey) {
		List<Long> ids = jdbc.query("SELECT id FROM accounts WHERE company_id=? AND system_key=? AND active=true",
				(rs, row) -> rs.getLong(1), companyId, systemKey);
		if (ids.isEmpty())
			throw new IllegalArgumentException(
					"System account " + systemKey + " is missing. Run the India chart-of-accounts setup first.");
		return ids.get(0);
	}

	public void requireOpenPeriod(long companyId, LocalDate date) {
		Integer open = jdbc.queryForObject(
				"SELECT count(*) FROM accounting_periods WHERE company_id=? AND start_date<=? AND end_date>=? AND status='OPEN'",
				Integer.class, companyId, date, date);
		if (open == null || open == 0)
			throw new IllegalArgumentException("No open accounting period contains " + date + ".");
	}

	public long createDraftJournal(long companyId, LocalDate date, String description, String idempotencyKey,
			long actorId, List<Line> lines) {
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		for (Line l : lines) {
			debits = debits.add(l.debit());
			credits = credits.add(l.credit());
		}
		if (lines.size() < 2 || debits.compareTo(credits) != 0)
			throw new IllegalStateException("Generated journal is not balanced.");
		Long id = jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,idempotency_key,created_by_user_id) VALUES (?,?,?,?,?) RETURNING id",
				Long.class, companyId, date, description, idempotencyKey, actorId);
		for (Line l : lines)
			jdbc.update(
					"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) VALUES (?,?,?,?,?,?)",
					companyId, id, l.accountId(), l.description(), l.debit(), l.credit());
		return id;
	}

	public void audit(long companyId, String action, String entityType, long entityId, String detail) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?,?,?,?,jsonb_build_object('detail',?))",
				companyId, SecurityContextHolder.getContext().getAuthentication().getName(), action, entityType,
				String.valueOf(entityId), detail);
	}
}
