package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Low-level draft-journal and period lookups used when other features create ledger entries. */
@Repository
public class LedgerRepository {
	private final JdbcTemplate jdbc;

	public LedgerRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Long> findActiveSystemAccountIds(long companyId, String systemKey) {
		return jdbc.query("SELECT id FROM accounts WHERE company_id=? AND system_key=? AND active=true",
				(rs, row) -> rs.getLong(1), companyId, systemKey);
	}

	public int countOpenPeriodsContaining(long companyId, LocalDate date) {
		Integer open = jdbc.queryForObject(
				"SELECT count(*) FROM accounting_periods WHERE company_id=? AND start_date<=? AND end_date>=? AND status='OPEN'",
				Integer.class, companyId, date, date);
		return open == null ? 0 : open;
	}

	public long insertDraftJournal(long companyId, LocalDate date, String description, String idempotencyKey,
			long actorId) {
		Long id = jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,idempotency_key,created_by_user_id) VALUES (?,?,?,?,?) RETURNING id",
				Long.class, companyId, date, description, idempotencyKey, actorId);
		return id;
	}

	public void insertJournalLine(long companyId, long journalId, long accountId, String description, BigDecimal debit,
			BigDecimal credit) {
		jdbc.update(
				"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) VALUES (?,?,?,?,?,?)",
				companyId, journalId, accountId, description, debit, credit);
	}
}