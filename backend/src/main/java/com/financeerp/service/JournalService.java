package com.financeerp.service;

import com.financeerp.dto.JournalDto;
import com.financeerp.repository.JournalRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class JournalService {
	private final JournalRepository journals;
	private final CompanyAccess access;

	public JournalService(JournalRepository journals, CompanyAccess access) {
		this.journals = journals;
		this.access = access;
	}

	@Transactional
	public Map<String, Object> create(long companyId, JournalDto.CreateJournalRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		if (req.lines().size() < 2)
			throw new IllegalArgumentException("A journal must have at least two lines.");
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		for (JournalDto.LineRequest line : req.lines()) {
			if (line.debit().signum() < 0 || line.credit().signum() < 0)
				throw new IllegalArgumentException("Amounts cannot be negative.");
			if ((line.debit().signum() > 0) == (line.credit().signum() > 0))
				throw new IllegalArgumentException("Each line must have exactly one positive debit or credit.");
			if (line.debit().scale() > 4 || line.credit().scale() > 4 || line.debit().precision() > 19
					|| line.credit().precision() > 19)
				throw new IllegalArgumentException(
						"Amounts must fit NUMERIC(19,4): maximum four decimal places and 15 integer digits.");
			if (journals.countActiveAccount(companyId, line.accountId()) == 0)
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
		Long id = journals.insertJournal(companyId, req.journalDate(), req.description().trim(),
				req.idempotencyKey(), actorId);
		for (JournalDto.LineRequest line : req.lines())
			journals.insertLine(companyId, id, line.accountId(), line.description(), line.debit(), line.credit());
		audit(companyId, "JOURNAL_CREATED", id, "DRAFT");
		return getJournal(companyId, id);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return journals.findByCompany(companyId);
	}

	public Map<String, Object> get(long companyId, long journalId) {
		return getJournal(companyId, journalId);
	}

	@Transactional
	public Map<String, Object> submit(long companyId, long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"DRAFT".equals(journal.get("status")))
			throw new IllegalArgumentException("Only draft journals can be submitted.");
		Map<String, Object> totals = journals.totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must have at least two balanced lines.");
		journals.markSubmitted(companyId, journalId, actorId);
		audit(companyId, "JOURNAL_SUBMITTED", journalId, "SUBMITTED");
		return getJournal(companyId, journalId);
	}

	@Transactional
	public Map<String, Object> approve(long companyId, long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "APPROVER"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"SUBMITTED".equals(journal.get("status")))
			throw new IllegalArgumentException("Only submitted journals can be approved.");
		Long submitter = (Long) journal.get("submittedByUserId");
		Long creator = (Long) journal.get("createdByUserId");
		boolean selfApproval = (submitter != null && submitter == actorId) || (creator != null && creator == actorId);
		if (selfApproval && !journals.isSelfApprovalAllowed(companyId))
			throw new AccessDeniedException(
					"Maker-checker control: the journal creator and submitter cannot approve their own journal. An owner can allow this in company settings if one person runs the books.");
		LocalDate date = ((java.sql.Date) journal.get("journalDate")).toLocalDate();
		requireOpenPeriod(companyId, date);
		Map<String, Object> totals = journals.totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must have at least two balanced lines.");
		journals.markApproved(companyId, journalId, actorId);
		audit(companyId, selfApproval ? "JOURNAL_SELF_APPROVED" : "JOURNAL_APPROVED", journalId, "APPROVED");
		return getJournal(companyId, journalId);
	}

	@Transactional
	public Map<String, Object> post(long companyId, long journalId) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT", "APPROVER"));
		Map<String, Object> journal = lockJournal(companyId, journalId);
		if (!"APPROVED".equals(journal.get("status")))
			throw new IllegalArgumentException(
					"Only approved journals can be posted. Submit and approve the journal first.");
		LocalDate date = ((java.sql.Date) journal.get("journalDate")).toLocalDate();
		requireOpenPeriod(companyId, date);
		Map<String, Object> totals = journals.totals(companyId, journalId);
		if (((Number) totals.get("lines")).intValue() < 2
				|| ((BigDecimal) totals.get("debit")).compareTo((BigDecimal) totals.get("credit")) != 0)
			throw new IllegalArgumentException("Journal must contain at least two balanced lines.");
		journals.markPosted(companyId, journalId, actorId);
		audit(companyId, "JOURNAL_POSTED", journalId, "POSTED");
		return getJournal(companyId, journalId);
	}

	@Transactional
	public Map<String, Object> reverse(long companyId, long journalId, JournalDto.ReverseJournalRequest request) {
		long actorId = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		Map<String, Object> original = journals.lockJournalForReversal(companyId, journalId)
				.orElseThrow(() -> new IllegalArgumentException("Journal not found."));
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
		Long reversalId = journals.insertReversalDraft(companyId, request.reversalDate(), description, journalId,
				actorId);
		journals.copyReversedLines(companyId, reversalId, journalId);
		if (journals.markReversed(companyId, journalId, reversalId) != 1)
			throw new IllegalArgumentException("This journal has already been reversed.");
		audit(companyId, "REVERSAL_DRAFT_CREATED", journalId, "REVERSAL_" + reversalId);
		return getJournal(companyId, reversalId);
	}

	private Map<String, Object> getJournal(long companyId, long id) {
		access.requireMembership(companyId);
		List<Map<String, Object>> heads = journals.findHead(companyId, id);
		if (heads.isEmpty())
			throw new IllegalArgumentException("Journal not found.");
		Map<String, Object> out = new LinkedHashMap<>(heads.get(0));
		out.put("lines", journals.findLines(companyId, id));
		return out;
	}

	private Map<String, Object> lockJournal(long companyId, long journalId) {
		return journals.lockJournal(companyId, journalId)
				.orElseThrow(() -> new IllegalArgumentException("Journal not found."));
	}

	private void requireOpenPeriod(long companyId, LocalDate date) {
		if (journals.lockOpenPeriodIds(companyId, date).isEmpty())
			throw new IllegalArgumentException("No open accounting period contains " + date
					+ ". Create or reopen the appropriate period before posting.");
	}

	private void audit(long companyId, String action, long id, String status) {
		journals.insertAuditEvent(companyId, SecurityContextHolder.getContext().getAuthentication().getName(),
				action, id, status);
	}
}
