package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Straight-line monthly recognition; a full month is recognised from the start month. Journals are drafts. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class RevenueScheduleController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public RevenueScheduleController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record ScheduleRequest(@NotNull LocalDate startDate, @Min(1) @Max(120) int months,
			@Size(max = 300) String description, BigDecimal amount, Long revenueAccountId) {
	}

	public record RecognizeRequest(@NotNull LocalDate asOf) {
	}

	@PostMapping("/sales-invoices/{invoiceId}/revenue-schedule")
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody ScheduleRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status,invoice_number AS number,invoice_date AS date,taxable_amount AS taxable FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE",
				companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> inv = rows.get(0);
		if (!"ISSUED".equals(inv.get("status")))
			throw new IllegalArgumentException("Revenue schedules can only be created for issued invoices.");
		BigDecimal taxable = (BigDecimal) inv.get("taxable");
		BigDecimal amount = req.amount() == null ? taxable : req.amount();
		gst.checkAmount(amount, 13, "Amount");
		if (amount.signum() <= 0 || amount.scale() > 2)
			throw new IllegalArgumentException("Amount must be positive with at most two decimal places.");
		amount = amount.setScale(2, RoundingMode.UNNECESSARY);
		BigDecimal scheduled = jdbc.queryForObject(
				"SELECT COALESCE(SUM(total_amount),0) FROM revenue_schedules WHERE company_id=? AND sales_invoice_id=?",
				BigDecimal.class, companyId, invoiceId);
		if (scheduled.add(amount).compareTo(taxable) > 0)
			throw new IllegalArgumentException("Scheduled revenue would exceed the invoice's taxable value (" + taxable.toPlainString() + ").");
		BigDecimal monthly = amount.divide(BigDecimal.valueOf(req.months()), 2, RoundingMode.DOWN);
		if (monthly.signum() <= 0)
			throw new IllegalArgumentException("Amount is too small to spread over " + req.months() + " months.");
		long revenue;
		if (req.revenueAccountId() != null) {
			gst.requireAccount(companyId, req.revenueAccountId(), "INCOME");
			revenue = req.revenueAccountId();
		} else {
			revenue = poster.systemAccount(companyId, "SALES");
		}
		long deferred = poster.systemAccount(companyId, "DEFERRED_REVENUE");
		LocalDate invoiceDate = ((java.sql.Date) inv.get("date")).toLocalDate();
		poster.requireOpenPeriod(companyId, invoiceDate);
		String description = req.description() == null || req.description().isBlank() ? "Revenue for " + inv.get("number")
				: req.description().trim();
		long journalId = poster.createDraftJournal(companyId, invoiceDate, "Defer revenue: " + description,
				"REVSCHED_DEFER:" + UUID.randomUUID(), actor,
				List.of(new LedgerPoster.Line(revenue, description, amount, BigDecimal.ZERO),
						new LedgerPoster.Line(deferred, description, BigDecimal.ZERO, amount)));
		Long id = jdbc.queryForObject(
				"INSERT INTO revenue_schedules(company_id,sales_invoice_id,description,total_amount,start_date,months,revenue_account_id,deferred_account_id,deferral_journal_id,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, invoiceId, description, amount, req.startDate(), req.months(), revenue, deferred,
				journalId, actor);
		YearMonth first = YearMonth.from(req.startDate());
		for (int i = 0; i < req.months(); i++) {
			BigDecimal part = i == req.months() - 1 ? amount.subtract(monthly.multiply(BigDecimal.valueOf(i))) : monthly;
			jdbc.update(
					"INSERT INTO revenue_schedule_entries(company_id,schedule_id,recognition_date,amount) VALUES (?,?,?,?)",
					companyId, id, first.plusMonths(i).atEndOfMonth(), part);
		}
		poster.audit(companyId, "REVENUE_SCHEDULE_CREATED", "REVENUE_SCHEDULE", id, description);
		return get(companyId, id);
	}

	/** Creates draft journals for due entries whose deferral is posted and whose period is open. */
	@PostMapping("/revenue-schedules/recognize")
	@Transactional
	public Map<String, Object> recognize(@PathVariable long companyId, @Valid @RequestBody RecognizeRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> due = jdbc.queryForList("""
				SELECT e.id,e.recognition_date AS date,e.amount,s.revenue_account_id AS revenue,
				       s.deferred_account_id AS deferred,s.description
				FROM revenue_schedule_entries e
				JOIN revenue_schedules s ON s.company_id=e.company_id AND s.id=e.schedule_id
				JOIN journal_entries d ON d.company_id=s.company_id AND d.id=s.deferral_journal_id AND d.status='POSTED'
				WHERE e.company_id=? AND e.journal_entry_id IS NULL AND e.recognition_date<=?
				ORDER BY e.recognition_date,e.id FOR UPDATE OF e
				""", companyId, req.asOf());
		int created = 0, skipped = 0;
		for (Map<String, Object> e : due) {
			LocalDate date = ((java.sql.Date) e.get("date")).toLocalDate();
			try {
				poster.requireOpenPeriod(companyId, date);
			} catch (IllegalArgumentException closed) {
				skipped++;
				continue;
			}
			long entryId = ((Number) e.get("id")).longValue();
			BigDecimal amount = (BigDecimal) e.get("amount");
			String label = "Recognise revenue: " + e.get("description");
			long journalId = poster.createDraftJournal(companyId, date, label, "REVREC:" + entryId, actor,
					List.of(new LedgerPoster.Line(((Number) e.get("deferred")).longValue(), label, amount, BigDecimal.ZERO),
							new LedgerPoster.Line(((Number) e.get("revenue")).longValue(), label, BigDecimal.ZERO, amount)));
			jdbc.update("UPDATE revenue_schedule_entries SET journal_entry_id=? WHERE company_id=? AND id=?", journalId,
					companyId, entryId);
			created++;
		}
		poster.audit(companyId, "REVENUE_RECOGNISED", "COMPANY", companyId, created + " drafted, " + skipped + " skipped");
		return Map.of("draftJournalsCreated", created, "skipped", skipped);
	}

	private static final String SELECT = """
			SELECT s.id,s.sales_invoice_id AS "salesInvoiceId",i.invoice_number AS "invoiceNumber",s.description,
			 s.total_amount AS "totalAmount",s.start_date AS "startDate",s.months,
			 COALESCE((SELECT SUM(e.amount) FROM revenue_schedule_entries e WHERE e.company_id=s.company_id AND e.schedule_id=s.id AND e.journal_entry_id IS NOT NULL),0) AS recognized,
			 dj.status AS "deferralJournalStatus"
			FROM revenue_schedules s
			JOIN sales_invoices i ON i.company_id=s.company_id AND i.id=s.sales_invoice_id
			JOIN journal_entries dj ON dj.company_id=s.company_id AND dj.id=s.deferral_journal_id
			""";

	@GetMapping("/revenue-schedules")
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : jdbc.queryForList(SELECT + " WHERE s.company_id=? ORDER BY s.id DESC", companyId))
			out.add(withRemaining(r));
		return out;
	}

	@GetMapping("/revenue-schedules/{scheduleId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long scheduleId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE s.company_id=? AND s.id=?", companyId,
				scheduleId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Revenue schedule not found.");
		Map<String, Object> out = withRemaining(rows.get(0));
		out.put("entries", jdbc.queryForList(
				"SELECT id,recognition_date AS \"recognitionDate\",amount,journal_entry_id AS \"journalEntryId\" FROM revenue_schedule_entries WHERE company_id=? AND schedule_id=? ORDER BY recognition_date",
				companyId, scheduleId));
		return out;
	}

	private static Map<String, Object> withRemaining(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>(row);
		out.put("remaining", ((BigDecimal) row.get("totalAmount")).subtract((BigDecimal) row.get("recognized")));
		return out;
	}
}
