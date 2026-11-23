package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JournalRepository {
	private final JdbcTemplate jdbc;

	public JournalRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int countActiveAccount(long companyId, Long accountId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true", Integer.class,
				companyId, accountId);
		return count == null ? 0 : count;
	}

	public Long insertJournal(long companyId, LocalDate journalDate, String description, String idempotencyKey,
			long createdByUserId) {
		return jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,idempotency_key,created_by_user_id) VALUES (?,?,?,?,?) RETURNING id",
				Long.class, companyId, journalDate, description, idempotencyKey, createdByUserId);
	}

	public void insertLine(long companyId, Long journalId, Long accountId, String description, BigDecimal debit,
			BigDecimal credit) {
		jdbc.update(
				"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) VALUES (?,?,?,?,?,?)",
				companyId, journalId, accountId, description, debit, credit);
	}

	public List<Map<String, Object>> findByCompany(long companyId) {
		return jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",journal_date AS \"journalDate\",description,status,created_at AS \"createdAt\",posted_at AS \"postedAt\",reversal_of_journal_id AS \"reversalOfJournalId\",reversed_by_journal_id AS \"reversedByJournalId\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\",submitted_at AS \"submittedAt\",approved_by_user_id AS \"approvedByUserId\",approved_at AS \"approvedAt\",posted_by_user_id AS \"postedByUserId\" FROM journal_entries WHERE company_id=? ORDER BY journal_date DESC,id DESC",
				companyId);
	}

	public List<Map<String, Object>> findHead(long companyId, long id) {
		return jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",journal_date AS \"journalDate\",description,status,created_at AS \"createdAt\",posted_at AS \"postedAt\",reversal_of_journal_id AS \"reversalOfJournalId\",reversed_by_journal_id AS \"reversedByJournalId\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\",submitted_at AS \"submittedAt\",approved_by_user_id AS \"approvedByUserId\",approved_at AS \"approvedAt\",posted_by_user_id AS \"postedByUserId\" FROM journal_entries WHERE company_id=? AND id=?",
				companyId, id);
	}

	public List<Map<String, Object>> findLines(long companyId, long id) {
		return jdbc.queryForList(
				"SELECT id,account_id AS \"accountId\",description,debit,credit FROM journal_lines WHERE company_id=? AND journal_entry_id=? ORDER BY id",
				companyId, id);
	}

	public Optional<Map<String, Object>> lockJournal(long companyId, long journalId) {
		return jdbc.queryForList(
				"SELECT id,status,journal_date AS \"journalDate\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\" FROM journal_entries WHERE company_id=? AND id=? FOR UPDATE",
				companyId, journalId).stream().findFirst();
	}

	public Optional<Map<String, Object>> lockJournalForReversal(long companyId, long journalId) {
		return jdbc.queryForList(
				"SELECT id,status,description,reversed_by_journal_id AS \"reversedBy\" FROM journal_entries WHERE company_id=? AND id=? FOR UPDATE",
				companyId, journalId).stream().findFirst();
	}

	public Map<String, Object> totals(long companyId, long journalId) {
		return jdbc.queryForObject(
				"SELECT count(*) AS lines,COALESCE(sum(debit),0) AS debit,COALESCE(sum(credit),0) AS credit FROM journal_lines WHERE company_id=? AND journal_entry_id=?",
				(rs, row) -> Map.of("lines", rs.getLong("lines"), "debit", rs.getBigDecimal("debit"), "credit",
						rs.getBigDecimal("credit")),
				companyId, journalId);
	}

	public void markSubmitted(long companyId, long journalId, long actorId) {
		jdbc.update(
				"UPDATE journal_entries SET status='SUBMITTED',submitted_by_user_id=?,submitted_at=now() WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
	}

	public void markApproved(long companyId, long journalId, long actorId) {
		jdbc.update(
				"UPDATE journal_entries SET status='APPROVED',approved_by_user_id=?,approved_at=now() WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
	}

	public void markPosted(long companyId, long journalId, long actorId) {
		jdbc.update(
				"UPDATE journal_entries SET status='POSTED',posted_at=now(),posted_by_user_id=? WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
	}

	public Long insertReversalDraft(long companyId, LocalDate reversalDate, String description, long originalId,
			long createdByUserId) {
		return jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,status,reversal_of_journal_id,created_by_user_id) VALUES (?,?,?,'DRAFT',?,?) RETURNING id",
				Long.class, companyId, reversalDate, description, originalId, createdByUserId);
	}

	public void copyReversedLines(long companyId, Long reversalId, long originalId) {
		jdbc.update(
				"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) SELECT company_id,?,account_id,COALESCE(description,'') || ' (reversal)',credit,debit FROM journal_lines WHERE company_id=? AND journal_entry_id=?",
				reversalId, companyId, originalId);
	}

	public int markReversed(long companyId, long originalId, Long reversalId) {
		return jdbc.update(
				"UPDATE journal_entries SET reversed_by_journal_id=? WHERE company_id=? AND id=? AND reversed_by_journal_id IS NULL",
				reversalId, companyId, originalId);
	}

	/** Locks the matching period so posting and period closure cannot race each other. */
	public List<Long> lockOpenPeriodIds(long companyId, LocalDate date) {
		return jdbc.query(
				"SELECT id FROM accounting_periods WHERE company_id=? AND start_date<=? AND end_date>=? AND status='OPEN' FOR UPDATE",
				(rs, row) -> rs.getLong(1), companyId, date, date);
	}

	public boolean isSelfApprovalAllowed(long companyId) {
		return Boolean.TRUE.equals(
				jdbc.queryForObject("SELECT allow_self_approval FROM companies WHERE id=?", Boolean.class, companyId));
	}

	public void insertAuditEvent(long companyId, String actor, String action, long id, String status) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?, ?, ?, 'JOURNAL', ?, jsonb_build_object('status',?))",
				companyId, actor, action, String.valueOf(id), status);
	}
}
