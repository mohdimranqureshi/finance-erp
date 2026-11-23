package com.financeerp.service;

import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.RevenueScheduleDto.RecognizeRequest;
import com.financeerp.dto.RevenueScheduleDto.ScheduleRequest;
import com.financeerp.repository.RevenueScheduleRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Straight-line monthly recognition; a full month is recognised from the start month. Journals are drafts. */
@Service
public class RevenueScheduleService {
	private final RevenueScheduleRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public RevenueScheduleService(RevenueScheduleRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	@Transactional
	public Map<String, Object> create(long companyId, long invoiceId, ScheduleRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = repository.lockSalesInvoice(companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> inv = rows.get(0);
		if (!"ISSUED".equals(inv.get("status")))
			throw new IllegalArgumentException("Revenue schedules can only be created for issued invoices.");
		BigDecimal taxable = (BigDecimal) inv.get("taxable");
		BigDecimal amount = req.amount() == null ? taxable : req.amount();
		gst.checkAmount(amount, 13, "Amount");
		// NUMERIC(19,4) columns come back with scale 4, so compare significant digits rather than raw scale.
		if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
			throw new IllegalArgumentException("Amount must be positive with at most two decimal places.");
		amount = amount.setScale(2, RoundingMode.UNNECESSARY);
		BigDecimal scheduled = repository.sumScheduledAmount(companyId, invoiceId);
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
				List.of(new LedgerLine(revenue, description, amount, BigDecimal.ZERO),
						new LedgerLine(deferred, description, BigDecimal.ZERO, amount)));
		Long id = repository.insertSchedule(companyId, invoiceId, description, amount, req.startDate(), req.months(),
				revenue, deferred, journalId, actor);
		YearMonth first = YearMonth.from(req.startDate());
		for (int i = 0; i < req.months(); i++) {
			BigDecimal part = i == req.months() - 1 ? amount.subtract(monthly.multiply(BigDecimal.valueOf(i))) : monthly;
			repository.insertScheduleEntry(companyId, id, first.plusMonths(i).atEndOfMonth(), part);
		}
		poster.audit(companyId, "REVENUE_SCHEDULE_CREATED", "REVENUE_SCHEDULE", id, description);
		return get(companyId, id);
	}

	/** Creates draft journals for due entries whose deferral is posted and whose period is open. */
	@Transactional
	public Map<String, Object> recognize(long companyId, RecognizeRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> due = repository.lockDueEntries(companyId, req.asOf());
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
					List.of(new LedgerLine(((Number) e.get("deferred")).longValue(), label, amount, BigDecimal.ZERO),
							new LedgerLine(((Number) e.get("revenue")).longValue(), label, BigDecimal.ZERO, amount)));
			repository.markEntryRecognized(companyId, entryId, journalId);
			created++;
		}
		poster.audit(companyId, "REVENUE_RECOGNISED", "COMPANY", companyId, created + " drafted, " + skipped + " skipped");
		return Map.of("draftJournalsCreated", created, "skipped", skipped);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : repository.findSchedules(companyId))
			out.add(withRemaining(r));
		return out;
	}

	public Map<String, Object> get(long companyId, long scheduleId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findSchedule(companyId, scheduleId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Revenue schedule not found.");
		Map<String, Object> out = withRemaining(rows.get(0));
		out.put("entries", repository.findScheduleEntries(companyId, scheduleId));
		return out;
	}

	private static Map<String, Object> withRemaining(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>(row);
		out.put("remaining", ((BigDecimal) row.get("totalAmount")).subtract((BigDecimal) row.get("recognized")));
		return out;
	}
}
