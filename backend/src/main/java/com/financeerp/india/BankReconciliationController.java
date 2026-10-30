package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/bank")
public class BankReconciliationController {
	private static final String SIGNED_PAYMENT = "CASE p.direction WHEN 'RECEIPT' THEN p.amount ELSE -p.amount END";
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public BankReconciliationController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record StatementLine(@NotNull LocalDate date, @Size(max = 300) String description, @NotNull BigDecimal amount,
			@Size(max = 100) String reference, @Size(max = 100) String externalId) {
	}

	public record ImportRequest(@NotNull Long bankAccountId, @NotEmpty @Size(max = 500) List<@Valid StatementLine> lines) {
	}

	public record MatchRequest(@NotNull Long paymentId) {
	}

	@PostMapping("/statement-lines")
	@Transactional
	public Map<String, Object> importLines(@PathVariable long companyId, @Valid @RequestBody ImportRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		gst.requireAccount(companyId, req.bankAccountId(), "ASSET");
		int imported = 0;
		for (StatementLine l : req.lines()) {
			if (l.amount().signum() == 0)
				throw new IllegalArgumentException("Statement amounts cannot be zero.");
			if (l.amount().scale() > 4 || l.amount().precision() - l.amount().scale() > 13)
				throw new IllegalArgumentException("Statement amount has too many digits.");
			imported += jdbc.update(
					"INSERT INTO bank_statement_lines(company_id,bank_account_id,txn_date,description,amount,reference,external_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
					companyId, req.bankAccountId(), l.date(), l.description() == null ? "" : l.description().trim(),
					l.amount(), l.reference(), l.externalId() == null || l.externalId().isBlank() ? null : l.externalId().trim());
		}
		poster.audit(companyId, "BANK_STATEMENT_IMPORTED", "BANK_ACCOUNT", req.bankAccountId(), imported + " lines");
		return Map.of("imported", imported, "skippedDuplicates", req.lines().size() - imported);
	}

	@GetMapping("/statement-lines")
	public List<Map<String, Object>> lines(@PathVariable long companyId, @RequestParam long bankAccountId,
			@RequestParam(defaultValue = "false") boolean unmatchedOnly) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id,txn_date AS \"txnDate\",description,amount,reference,matched_payment_id AS \"matchedPaymentId\" FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND (? = false OR (matched_payment_id IS NULL AND journal_entry_id IS NULL)) ORDER BY txn_date DESC,id DESC LIMIT 200",
				companyId, bankAccountId, unmatchedOnly);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> row = new LinkedHashMap<>(r);
			if (r.get("matchedPaymentId") == null) {
				LocalDate d = ((java.sql.Date) r.get("txnDate")).toLocalDate();
				row.put("suggestions", jdbc.queryForList(
						"SELECT p.id AS \"paymentId\",p.direction,p.payment_date AS \"paymentDate\",p.reference,p.amount FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED' WHERE p.company_id=? AND p.bank_account_id=? AND "
								+ SIGNED_PAYMENT
								+ "=? AND p.payment_date BETWEEN ? AND ? AND NOT EXISTS (SELECT 1 FROM bank_statement_lines s WHERE s.company_id=p.company_id AND s.matched_payment_id=p.id) ORDER BY p.payment_date",
						companyId, bankAccountId, r.get("amount"), d.minusDays(5), d.plusDays(5)));
			}
			out.add(row);
		}
		return out;
	}

	@PostMapping("/statement-lines/{lineId}/match")
	@Transactional
	public Map<String, Object> match(@PathVariable long companyId, @PathVariable long lineId,
			@Valid @RequestBody MatchRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> lineRows = jdbc.queryForList(
				"SELECT bank_account_id AS bank,amount,matched_payment_id AS matched,journal_entry_id AS journal FROM bank_statement_lines WHERE company_id=? AND id=? FOR UPDATE",
				companyId, lineId);
		if (lineRows.isEmpty())
			throw new IllegalArgumentException("Statement line not found.");
		Map<String, Object> line = lineRows.get(0);
		if (line.get("matched") != null)
			throw new IllegalArgumentException("This statement line is already matched.");
		if (line.get("journal") != null)
			throw new IllegalArgumentException("This statement line is already explained by a journal.");
		List<Map<String, Object>> pay = jdbc.queryForList("SELECT p.bank_account_id AS bank," + SIGNED_PAYMENT
				+ " AS signed,j.status AS status FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id WHERE p.company_id=? AND p.id=?",
				companyId, req.paymentId());
		if (pay.isEmpty())
			throw new IllegalArgumentException("Payment not found.");
		Map<String, Object> p = pay.get(0);
		if (!"POSTED".equals(p.get("status")))
			throw new IllegalArgumentException("Only payments whose journal is posted can be reconciled.");
		if (((Number) p.get("bank")).longValue() != ((Number) line.get("bank")).longValue())
			throw new IllegalArgumentException("Payment belongs to a different bank account.");
		if (((BigDecimal) p.get("signed")).compareTo((BigDecimal) line.get("amount")) != 0)
			throw new IllegalArgumentException("Amount (and direction) of the payment and statement line differ.");
		jdbc.update(
				"UPDATE bank_statement_lines SET matched_payment_id=?,matched_by_user_id=?,matched_at=now() WHERE company_id=? AND id=?",
				req.paymentId(), actor, companyId, lineId);
		poster.audit(companyId, "BANK_LINE_MATCHED", "BANK_STATEMENT_LINE", lineId, "payment " + req.paymentId());
		return Map.of("lineId", lineId, "paymentId", req.paymentId());
	}

	@PostMapping("/statement-lines/{lineId}/unmatch")
	@Transactional
	public Map<String, Object> unmatch(@PathVariable long companyId, @PathVariable long lineId) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		int n = jdbc.update(
				"UPDATE bank_statement_lines SET matched_payment_id=NULL,matched_by_user_id=NULL,matched_at=NULL WHERE company_id=? AND id=? AND matched_payment_id IS NOT NULL",
				companyId, lineId);
		if (n == 0)
			throw new IllegalArgumentException("Statement line not found or not matched.");
		poster.audit(companyId, "BANK_LINE_UNMATCHED", "BANK_STATEMENT_LINE", lineId, "unmatched");
		return Map.of("lineId", lineId);
	}

	@GetMapping("/reconciliation")
	public Map<String, Object> reconciliation(@PathVariable long companyId, @RequestParam long bankAccountId,
			@RequestParam LocalDate asOf, @RequestParam(required = false) BigDecimal openingBalance) {
		access.requireMembership(companyId);
		BigDecimal book = jdbc.queryForObject(
				"SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN journal_entries j ON j.company_id=l.company_id AND j.id=l.journal_entry_id WHERE l.company_id=? AND l.account_id=? AND j.status='POSTED' AND j.journal_date<=?",
				BigDecimal.class, companyId, bankAccountId, asOf);
		BigDecimal movement = jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND txn_date<=?",
				BigDecimal.class, companyId, bankAccountId, asOf);
		BigDecimal statement = movement.add(openingBalance == null ? BigDecimal.ZERO : openingBalance);
		Map<String, Object> unmatchedLines = jdbc.queryForMap(
				"SELECT count(*) AS count,COALESCE(SUM(amount),0) AS total FROM bank_statement_lines WHERE company_id=? AND bank_account_id=? AND txn_date<=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL",
				companyId, bankAccountId, asOf);
		Map<String, Object> unmatchedPayments = jdbc.queryForMap("SELECT count(*) AS count,COALESCE(SUM("
				+ SIGNED_PAYMENT
				+ "),0) AS total FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id AND j.status='POSTED' WHERE p.company_id=? AND p.bank_account_id=? AND p.payment_date<=? AND NOT EXISTS (SELECT 1 FROM bank_statement_lines s WHERE s.company_id=p.company_id AND s.matched_payment_id=p.id)",
				companyId, bankAccountId, asOf);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("asOf", asOf);
		out.put("bookBalance", book);
		out.put("statementBalance", statement);
		out.put("difference", statement.subtract(book));
		out.put("unmatchedStatementLines", unmatchedLines);
		out.put("unmatchedPayments", unmatchedPayments);
		return out;
	}
}
