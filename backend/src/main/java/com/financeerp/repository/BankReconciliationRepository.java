package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class BankReconciliationRepository {
	private static final String SIGNED_PAYMENT = "CASE p.direction WHEN 'RECEIPT' THEN p.amount ELSE -p.amount END";
	private final JdbcTemplate jdbc;

	public BankReconciliationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int insertStatementLine(long companyId, Long bankAccountId, LocalDate date, String description, BigDecimal amount,
			String reference, String externalId) {
		return jdbc.update(
				"INSERT INTO bank_statement_lines(company_id,bank_account_id,txn_date,description,amount,reference,external_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
				companyId, bankAccountId, date, description, amount, reference, externalId);
	}

	public List<Map<String, Object>> findStatementLines(long companyId, long bankAccountId, boolean unmatchedOnly) {
		return jdbc.queryForList(
				"SELECT id,txn_date AS \"txnDate\",description,amount,reference,matched_payment_id AS \"matchedPaymentId\" FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND (? = false OR (matched_payment_id IS NULL AND journal_entry_id IS NULL)) ORDER BY txn_date DESC,id DESC LIMIT 200",
				companyId, bankAccountId, unmatchedOnly);
	}

	public List<Map<String, Object>> findPaymentSuggestions(long companyId, long bankAccountId, Object amount,
			LocalDate from, LocalDate to) {
		return jdbc.queryForList(
				"SELECT p.id AS \"paymentId\",p.direction,p.payment_date AS \"paymentDate\",p.reference,p.amount FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED' WHERE p.company_id=? AND p.bank_account_id=? AND "
						+ SIGNED_PAYMENT
						+ "=? AND p.payment_date BETWEEN ? AND ? AND NOT EXISTS (SELECT 1 FROM bank_statement_lines s WHERE s.company_id=p.company_id AND s.matched_payment_id=p.id) ORDER BY p.payment_date",
				companyId, bankAccountId, amount, from, to);
	}

	public List<Map<String, Object>> lockStatementLine(long companyId, long lineId) {
		return jdbc.queryForList(
				"SELECT bank_account_id AS bank,amount,matched_payment_id AS matched,journal_entry_id AS journal FROM bank_statement_lines WHERE company_id=? AND id=? FOR UPDATE",
				companyId, lineId);
	}

	public List<Map<String, Object>> findPaymentForMatch(long companyId, long paymentId) {
		return jdbc.queryForList("SELECT p.bank_account_id AS bank," + SIGNED_PAYMENT
				+ " AS signed,j.status AS status FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id WHERE p.company_id=? AND p.id=?",
				companyId, paymentId);
	}

	public void matchStatementLine(long companyId, long lineId, Long paymentId, long actor) {
		jdbc.update(
				"UPDATE bank_statement_lines SET matched_payment_id=?,matched_by_user_id=?,matched_at=now() WHERE company_id=? AND id=?",
				paymentId, actor, companyId, lineId);
	}

	public int unmatchStatementLine(long companyId, long lineId) {
		return jdbc.update(
				"UPDATE bank_statement_lines SET matched_payment_id=NULL,matched_by_user_id=NULL,matched_at=NULL WHERE company_id=? AND id=? AND matched_payment_id IS NOT NULL",
				companyId, lineId);
	}

	public BigDecimal bookBalance(long companyId, long bankAccountId, LocalDate asOf) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries j ON j.company_id=l.company_id AND j.id=l.journal_entry_id WHERE l.company_id=? AND l.account_id=? AND j.status='POSTED' AND j.journal_date<=?",
				BigDecimal.class, companyId, bankAccountId, asOf);
	}

	public BigDecimal statementMovement(long companyId, long bankAccountId, LocalDate asOf) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND txn_date<=?",
				BigDecimal.class, companyId, bankAccountId, asOf);
	}

	public Map<String, Object> unmatchedStatementLines(long companyId, long bankAccountId, LocalDate asOf) {
		return jdbc.queryForMap(
				"SELECT count(*) AS count,COALESCE(SUM(amount),0) AS total FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND txn_date<=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL",
				companyId, bankAccountId, asOf);
	}

	public Map<String, Object> unmatchedPayments(long companyId, long bankAccountId, LocalDate asOf) {
		return jdbc.queryForMap("SELECT count(*) AS count,COALESCE(SUM(" + SIGNED_PAYMENT
				+ "),0) AS total FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED' WHERE p.company_id=? AND p.bank_account_id=? AND p.payment_date<=? AND NOT EXISTS (SELECT 1 FROM bank_statement_lines s WHERE s.company_id=p.company_id AND s.matched_payment_id=p.id)",
				companyId, bankAccountId, asOf);
	}
}
