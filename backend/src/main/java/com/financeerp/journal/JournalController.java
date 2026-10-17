package com.financeerp.journal;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/journals")
public class JournalController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public JournalController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record LineRequest(@NotNull Long accountId, String description, @NotNull @DecimalMin("0.0") BigDecimal debit,
			@NotNull @DecimalMin("0.0") BigDecimal credit) {
	}

	public record CreateJournalRequest(@NotNull LocalDate journalDate, @NotBlank String description,
			@NotEmpty List<@Valid LineRequest> lines, String idempotencyKey) {
	}

	public record ReverseJournalRequest(@NotNull LocalDate reversalDate, String description) {
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateJournalRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		if (req.lines().size() < 2)
			throw new IllegalArgumentException("A journal must have at least two lines.");
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		for (LineRequest line : req.lines()) {
			if (line.debit().signum() < 0 || line.credit().signum() < 0)
				throw new IllegalArgumentException("Amounts cannot be negative.");
			if ((line.debit().signum() > 0) == (line.credit().signum() > 0))
				throw new IllegalArgumentException("Each line must have exactly one positive debit or credit.");
			if (line.debit().scale() > 4 || line.credit().scale() > 4 || line.debit().precision() > 19
					|| line.credit().precision() > 19)
				throw new IllegalArgumentException(
						"Amounts must fit NUMERIC(19,4): maximum four decimal places and 15 integer digits.");
			Integer count = jdbc.queryForObject(
					"SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true", Integer.class,
					companyId, line.accountId());
			if (count == null || count == 0)
				throw new IllegalArgumentException("An account is inactive or does not belong to this company.");
			debits = debits.add(line.debit());
			credits = credits.add(line.credit());
		}
		if (debits.compareTo(credits) != 0)
			throw new IllegalArgumentException("Journal is not balanced: debit total must equal credit total.");
		long actorId = access.currentUserId();
		if (req.description().trim().length() > 500)
			throw new IllegalArgumentException("Description must be 500 characters or fewer.");
		if (req.idempotencyKey() != null && req.idempotencyKey().length() > 100)
			throw new IllegalArgumentException("Idempotency key must be 100 characters or fewer.");
		Long id = jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,idempotency_key,created_by_user_id) VALUES (?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.journalDate(), req.description().trim(), req.idempotencyKey(), actorId);
		for (LineRequest line : req.lines())
			jdbc.update(
					"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) VALUES (?,?,?,?,?,?)",
					companyId, id, line.accountId(), line.description(), line.debit(), line.credit());
		audit(companyId, "JOURNAL_CREATED", id, "DRAFT");
		return getJournal(companyId, id);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",journal_date AS \"journalDate\",description,status,created_at AS \"createdAt\",posted_at AS \"postedAt\",reversal_of_journal_id AS \"reversalOfJournalId\",reversed_by_journal_id AS \"reversedByJournalId\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\",submitted_at AS \"submittedAt\",approved_by_user_id AS \"approvedByUserId\",approved_at AS \"approvedAt\",posted_by_user_id AS \"postedByUserId\" FROM journal_entries WHERE company_id=? ORDER BY journal_date DESC,id DESC",
				companyId);
	}

	@GetMapping("/{journalId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long journalId) {
		return getJournal(companyId, journalId);
	}

	@PostMapping("/{journalId}/submit")
	@Transactional
	public Map<String, Object> submit(@PathVariable long companyId, @PathVariable long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"DRAFT".equals(journal.get("status")))
			throw new IllegalArgumentException("Only draft journals can be submitted.");
		Map<String, Object> totals = totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must have at least two balanced lines.");
		jdbc.update(
				"UPDATE journal_entries SET status='SUBMITTED',submitted_by_user_id=?,submitted_at=now() WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
		audit(companyId, "JOURNAL_SUBMITTED", journalId, "SUBMITTED");
		return getJournal(companyId, journalId);
	}

	@PostMapping("/{journalId}/approve")
	@Transactional
	public Map<String, Object> approve(@PathVariable long companyId, @PathVariable long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "APPROVER"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"SUBMITTED".equals(journal.get("status")))
			throw new IllegalArgumentException("Only submitted journals can be approved.");
		Long submitter = (Long) journal.get("submittedByUserId");
		Long creator = (Long) journal.get("createdByUserId");
		if ((submitter != null && submitter == actorId) || (creator != null && creator == actorId))
			throw new org.springframework.security.access.AccessDeniedException(
					"Maker-checker control: the journal creator and submitter cannot approve their own journal.");
		LocalDate date = ((java.sql.Date) journal.get("journalDate")).toLocalDate();
		requireOpenPeriod(companyId, date);
		Map<String, Object> totals = totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must have at least two balanced lines.");
		jdbc.update(
				"UPDATE journal_entries SET status='APPROVED',approved_by_user_id=?,approved_at=now() WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
		audit(companyId, "JOURNAL_APPROVED", journalId, "APPROVED");
		return getJournal(companyId, journalId);
	}

	@PostMapping("/{journalId}/post")
	@Transactional
	public Map<String, Object> post(@PathVariable long companyId, @PathVariable long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT", "APPROVER"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"APPROVED".equals(journal.get("status")))
			throw new IllegalArgumentException(
					"Only approved journals can be posted. Submit and approve the journal first.");
		LocalDate date = ((java.sql.Date) journal.get("journalDate")).toLocalDate();
		requireOpenPeriod(companyId, date);
		Map<String, Object> totals = totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must contain at least two balanced lines.");
		jdbc.update(
				"UPDATE journal_entries SET status='POSTED',posted_at=now(),posted_by_user_id=? WHERE company_id=? AND id=?",
				actorId, companyId, journalId);
		audit(companyId, "JOURNAL_POSTED", journalId, "POSTED");
		return getJournal(companyId, journalId);
	}

	@PostMapping("/{journalId}/reverse")
	@Transactional
	public Map<String, Object> reverse(@PathVariable long companyId, @PathVariable long journalId,
			@Valid @RequestBody ReverseJournalRequest request) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		Map<String, Object> original;
		try {
			original = jdbc.queryForMap(
					"SELECT id,status,description,reversed_by_journal_id AS \"reversedBy\" FROM journal_entries WHERE company_id=? AND id=? FOR UPDATE",
					companyId, journalId);
		} catch (EmptyResultDataAccessException ex) {
			throw new IllegalArgumentException("Journal not found.");
		}
		if (!"POSTED".equals(original.get("status")))
			throw new IllegalArgumentException("Only a posted journal can be reversed.");
		if (original.get("reversedBy") != null)
			throw new IllegalArgumentException("This journal has already been reversed.");
		requireOpenPeriod(companyId, request.reversalDate());
		String description = request.description() == null || request.description().isBlank()
				? "Reversal of journal #" + journalId
				: request.description().trim();
		if (description.length() > 500)
			throw new IllegalArgumentException("Description must be 500 characters or fewer.");
		Long reversalId = jdbc.queryForObject(
				"INSERT INTO journal_entries(company_id,journal_date,description,status,reversal_of_journal_id,created_by_user_id) VALUES (?,?,?,'DRAFT',?,?) RETURNING id",
				Long.class, companyId, request.reversalDate(), description, journalId, actorId);
		jdbc.update(
				"INSERT INTO journal_lines(company_id,journal_entry_id,account_id,description,debit,credit) SELECT company_id,?,account_id,COALESCE(description,'') || ' (reversal)',credit,debit FROM journal_lines WHERE company_id=? AND journal_entry_id=?",
				reversalId, companyId, journalId);
		int changed = jdbc.update(
				"UPDATE journal_entries SET reversed_by_journal_id=? WHERE company_id=? AND id=? AND reversed_by_journal_id IS NULL",
				reversalId, companyId, journalId);
		if (changed != 1)
			throw new IllegalArgumentException("This journal has already been reversed.");
		audit(companyId, "REVERSAL_DRAFT_CREATED", journalId, "REVERSAL_" + reversalId);
		return getJournal(companyId, reversalId);
	}

	private Map<String, Object> getJournal(long companyId, long id) {
		access.requireMembership(companyId);
		List<Map<String, Object>> heads = jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",journal_date AS \"journalDate\",description,status,created_at AS \"createdAt\",posted_at AS \"postedAt\",reversal_of_journal_id AS \"reversalOfJournalId\",reversed_by_journal_id AS \"reversedByJournalId\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\",submitted_at AS \"submittedAt\",approved_by_user_id AS \"approvedByUserId\",approved_at AS \"approvedAt\",posted_by_user_id AS \"postedByUserId\" FROM journal_entries WHERE company_id=? AND id=?",
				companyId, id);
		if (heads.isEmpty())
			throw new IllegalArgumentException("Journal not found.");
		Map<String, Object> out = new LinkedHashMap<>(heads.get(0));
		out.put("lines", jdbc.queryForList(
				"SELECT id,account_id AS \"accountId\",description,debit,credit FROM journal_lines WHERE company_id=? AND journal_entry_id=? ORDER BY id",
				companyId, id));
		return out;
	}

	private Map<String, Object> lockJournal(long companyId, long journalId) {
		try {
			return jdbc.queryForMap(
					"SELECT id,status,journal_date AS \"journalDate\",created_by_user_id AS \"createdByUserId\",submitted_by_user_id AS \"submittedByUserId\" FROM journal_entries WHERE company_id=? AND id=? FOR UPDATE",
					companyId, journalId);
		} catch (EmptyResultDataAccessException ex) {
			throw new IllegalArgumentException("Journal not found.");
		}
	}

	private Map<String, Object> totals(long companyId, long journalId) {
		return jdbc.queryForObject(
				"SELECT count(*) AS lines,COALESCE(sum(debit),0) AS debit,COALESCE(sum(credit),0) AS credit FROM journal_lines WHERE company_id=? AND journal_entry_id=?",
				(rs, row) -> Map.of("lines", rs.getLong("lines"), "debit", rs.getBigDecimal("debit"), "credit",
						rs.getBigDecimal("credit")),
				companyId, journalId);
	}

	private void requireOpenPeriod(long companyId, LocalDate date) {
		// Lock the matching period so posting and period closure cannot race each
		// other.
		List<Long> openPeriods = jdbc.query(
				"SELECT id FROM accounting_periods WHERE company_id=? AND start_date<=? AND end_date>=? AND status='OPEN' FOR UPDATE",
				(rs, row) -> rs.getLong(1), companyId, date, date);
		if (openPeriods.isEmpty())
			throw new IllegalArgumentException("No open accounting period contains " + date
					+ ". Create or reopen the appropriate period before posting.");
	}

	private void audit(long companyId, String action, long id, String status) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?, ?, ?, 'JOURNAL', ?, jsonb_build_object('status',?))",
				companyId, org.springframework.security.core.context.SecurityContextHolder.getContext()
						.getAuthentication().getName(),
				action, String.valueOf(id), status);
	}
}
