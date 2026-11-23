package com.financeerp.repository;

import static com.financeerp.repository.OpeningItemSql.ITEM_OUTSTANDING;
import static com.financeerp.repository.OpeningItemSql.JOURNAL_REVERSED;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class OpeningBalanceRepository {
	private final JdbcTemplate jdbc;

	public OpeningBalanceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void lockCompany(long companyId) {
		jdbc.queryForObject("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, companyId);
	}

	public List<Map<String, Object>> findActiveBatches(long companyId) {
		return jdbc.queryForList("SELECT b.id,b.journal_entry_id AS \"journalId\",j.status,"
				+ JOURNAL_REVERSED + " AS reversed FROM opening_balance_batches b JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id"
				+ " WHERE b.company_id=? AND b.status='ACTIVE'", companyId);
	}

	public void supersedeBatch(long companyId, Object batchId) {
		jdbc.update("UPDATE opening_balance_batches SET status='SUPERSEDED',superseded_at=now() WHERE company_id=? AND id=?",
				companyId, batchId);
	}

	public int countActiveAccount(long companyId, Long accountId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true",
				Integer.class, companyId, accountId);
		return n == null ? 0 : n;
	}

	public Long insertBatch(long companyId, LocalDate asOfDate, BigDecimal receivableTotal, BigDecimal payableTotal,
			BigDecimal equityAdjustment, long journalId, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO opening_balance_batches(company_id,as_of_date,receivable_total,payable_total,equity_adjustment,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, asOfDate, receivableTotal, payableTotal, equityAdjustment, journalId, actor);
	}

	public void insertItem(long companyId, long batchId, String type, Long partyId, String documentNumber,
			LocalDate documentDate, LocalDate dueDate, BigDecimal amount) {
		jdbc.update(
				"INSERT INTO opening_balance_items(company_id,batch_id,item_type,party_id,document_number,document_date,due_date,amount) VALUES (?,?,?,?,?,?,?,?)",
				companyId, batchId, type, partyId, documentNumber, documentDate, dueDate, amount);
	}

	public List<Map<String, Object>> findCurrentBatch(long companyId) {
		return jdbc.queryForList("""
				SELECT b.id,b.as_of_date AS "asOfDate",b.status,b.receivable_total AS "receivableTotal",
				 b.payable_total AS "payableTotal",b.equity_adjustment AS "equityAdjustment",
				 b.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",
				 j.reversed_by_journal_id AS "reversedByJournalId",""" + JOURNAL_REVERSED + """
				 AS "journalReversed",b.created_by_user_id AS "createdByUserId",b.created_at AS "createdAt",
				 b.superseded_at AS "supersededAt"
				FROM opening_balance_batches b JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
				WHERE b.company_id=? ORDER BY (b.status='ACTIVE') DESC,b.id DESC LIMIT 1
				""", companyId);
	}

	public List<Map<String, Object>> findItems(long companyId, long batchId, String type) {
		return jdbc.queryForList("SELECT o.id,o.party_id AS \"partyId\",p.name AS party,o.document_number AS \"documentNumber\","
				+ "o.document_date AS \"documentDate\",o.due_date AS \"dueDate\",o.amount," + ITEM_OUTSTANDING
				+ " AS outstanding FROM opening_balance_items o JOIN parties p ON p.company_id=o.company_id AND p.id=o.party_id"
				+ " WHERE o.company_id=? AND o.batch_id=? AND o.item_type=? ORDER BY o.document_date,o.id",
				companyId, batchId, type);
	}
}
