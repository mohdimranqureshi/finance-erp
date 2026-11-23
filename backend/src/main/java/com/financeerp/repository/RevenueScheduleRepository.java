package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class RevenueScheduleRepository {
	private static final String SELECT = """
			SELECT s.id,s.sales_invoice_id AS "salesInvoiceId",i.invoice_number AS "invoiceNumber",s.description,
			 s.total_amount AS "totalAmount",s.start_date AS "startDate",s.months,
			 COALESCE((SELECT SUM(e.amount) FROM revenue_schedule_entries e WHERE e.company_id=s.company_id AND e.schedule_id=s.id AND e.journal_entry_id IS NOT NULL),0) AS recognized,
			 dj.status AS "deferralJournalStatus"
			FROM revenue_schedules s
			JOIN sales_invoices i ON i.company_id=s.company_id AND i.id=s.sales_invoice_id
			JOIN journal_entries dj ON dj.company_id=s.company_id AND dj.id=s.deferral_journal_id
			""";
	private final JdbcTemplate jdbc;

	public RevenueScheduleRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> lockSalesInvoice(long companyId, long invoiceId) {
		return jdbc.queryForList(
				"SELECT status,invoice_number AS number,invoice_date AS date,taxable_amount AS taxable FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE",
				companyId, invoiceId);
	}

	public BigDecimal sumScheduledAmount(long companyId, long invoiceId) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(total_amount),0) FROM revenue_schedules WHERE company_id=? AND sales_invoice_id=?",
				BigDecimal.class, companyId, invoiceId);
	}

	public Long insertSchedule(long companyId, long invoiceId, String description, BigDecimal amount,
			LocalDate startDate, int months, long revenueAccountId, long deferredAccountId, long journalId,
			long actor) {
		return jdbc.queryForObject(
				"INSERT INTO revenue_schedules(company_id,sales_invoice_id,description,total_amount,start_date,months,revenue_account_id,deferred_account_id,deferral_journal_id,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, invoiceId, description, amount, startDate, months, revenueAccountId,
				deferredAccountId, journalId, actor);
	}

	public void insertScheduleEntry(long companyId, Long scheduleId, LocalDate recognitionDate, BigDecimal amount) {
		jdbc.update(
				"INSERT INTO revenue_schedule_entries(company_id,schedule_id,recognition_date,amount) VALUES (?,?,?,?)",
				companyId, scheduleId, recognitionDate, amount);
	}

	public List<Map<String, Object>> lockDueEntries(long companyId, LocalDate asOf) {
		return jdbc.queryForList("""
				SELECT e.id,e.recognition_date AS date,e.amount,s.revenue_account_id AS revenue,
				       s.deferred_account_id AS deferred,s.description
				FROM revenue_schedule_entries e
				JOIN revenue_schedules s ON s.company_id=e.company_id AND s.id=e.schedule_id
				JOIN journal_entries d ON d.company_id=s.company_id AND d.id=s.deferral_journal_id AND d.status='POSTED'
				WHERE e.company_id=? AND e.journal_entry_id IS NULL AND e.recognition_date<=?
				ORDER BY e.recognition_date,e.id FOR UPDATE OF e
				""", companyId, asOf);
	}

	public void markEntryRecognized(long companyId, long entryId, long journalId) {
		jdbc.update("UPDATE revenue_schedule_entries SET journal_entry_id=? WHERE company_id=? AND id=?", journalId,
				companyId, entryId);
	}

	public List<Map<String, Object>> findSchedules(long companyId) {
		return jdbc.queryForList(SELECT + " WHERE s.company_id=? ORDER BY s.id DESC", companyId);
	}

	public List<Map<String, Object>> findSchedule(long companyId, long scheduleId) {
		return jdbc.queryForList(SELECT + " WHERE s.company_id=? AND s.id=?", companyId, scheduleId);
	}

	public List<Map<String, Object>> findScheduleEntries(long companyId, long scheduleId) {
		return jdbc.queryForList(
				"SELECT id,recognition_date AS \"recognitionDate\",amount,journal_entry_id AS \"journalEntryId\" FROM revenue_schedule_entries WHERE company_id=? AND schedule_id=? ORDER BY recognition_date",
				companyId, scheduleId);
	}
}
