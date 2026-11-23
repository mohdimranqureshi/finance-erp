package com.financeerp.service;

import com.financeerp.dto.BankReconciliationDto.ImportRequest;
import com.financeerp.dto.BankReconciliationDto.MatchRequest;
import com.financeerp.dto.BankReconciliationDto.StatementLine;
import com.financeerp.repository.BankReconciliationRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class BankReconciliationService {
	private final BankReconciliationRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public BankReconciliationService(BankReconciliationRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	@Transactional
	public Map<String, Object> importLines(long companyId, ImportRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		gst.requireAccount(companyId, req.bankAccountId(), "ASSET");
		int imported = 0;
		for (StatementLine l : req.lines()) {
			if (l.amount().signum() == 0)
				throw new IllegalArgumentException("Statement amounts cannot be zero.");
			if (l.amount().scale() > 4 || l.amount().precision() - l.amount().scale() > 13)
				throw new IllegalArgumentException("Statement amount has too many digits.");
			imported += repository.insertStatementLine(companyId, req.bankAccountId(), l.date(),
					l.description() == null ? "" : l.description().trim(), l.amount(), l.reference(),
					l.externalId() == null || l.externalId().isBlank() ? null : l.externalId().trim());
		}
		poster.audit(companyId, "BANK_STATEMENT_IMPORTED", "BANK_ACCOUNT", req.bankAccountId(), imported + " lines");
		return Map.of("imported", imported, "skippedDuplicates", req.lines().size() - imported);
	}

	public List<Map<String, Object>> lines(long companyId, long bankAccountId, boolean unmatchedOnly) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findStatementLines(companyId, bankAccountId, unmatchedOnly);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> row = new LinkedHashMap<>(r);
			if (r.get("matchedPaymentId") == null) {
				LocalDate d = ((java.sql.Date) r.get("txnDate")).toLocalDate();
				row.put("suggestions", repository.findPaymentSuggestions(companyId, bankAccountId, r.get("amount"),
						d.minusDays(5), d.plusDays(5)));
			}
			out.add(row);
		}
		return out;
	}

	@Transactional
	public Map<String, Object> match(long companyId, long lineId, MatchRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> lineRows = repository.lockStatementLine(companyId, lineId);
		if (lineRows.isEmpty())
			throw new IllegalArgumentException("Statement line not found.");
		Map<String, Object> line = lineRows.get(0);
		if (line.get("matched") != null)
			throw new IllegalArgumentException("This statement line is already matched.");
		if (line.get("journal") != null)
			throw new IllegalArgumentException("This statement line is already explained by a journal.");
		List<Map<String, Object>> pay = repository.findPaymentForMatch(companyId, req.paymentId());
		if (pay.isEmpty())
			throw new IllegalArgumentException("Payment not found.");
		Map<String, Object> p = pay.get(0);
		if (!"POSTED".equals(p.get("status")))
			throw new IllegalArgumentException("Only payments whose journal is posted can be reconciled.");
		if (((Number) p.get("bank")).longValue() != ((Number) line.get("bank")).longValue())
			throw new IllegalArgumentException("Payment belongs to a different bank account.");
		if (((BigDecimal) p.get("signed")).compareTo((BigDecimal) line.get("amount")) != 0)
			throw new IllegalArgumentException("Amount (and direction) of the payment and statement line differ.");
		repository.matchStatementLine(companyId, lineId, req.paymentId(), actor);
		poster.audit(companyId, "BANK_LINE_MATCHED", "BANK_STATEMENT_LINE", lineId, "payment " + req.paymentId());
		return Map.of("lineId", lineId, "paymentId", req.paymentId());
	}

	@Transactional
	public Map<String, Object> unmatch(long companyId, long lineId) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		int n = repository.unmatchStatementLine(companyId, lineId);
		if (n == 0)
			throw new IllegalArgumentException("Statement line not found or not matched.");
		poster.audit(companyId, "BANK_LINE_UNMATCHED", "BANK_STATEMENT_LINE", lineId, "unmatched");
		return Map.of("lineId", lineId);
	}

	public Map<String, Object> reconciliation(long companyId, long bankAccountId, LocalDate asOf,
			BigDecimal openingBalance) {
		access.requireMembership(companyId);
		BigDecimal book = repository.bookBalance(companyId, bankAccountId, asOf);
		BigDecimal movement = repository.statementMovement(companyId, bankAccountId, asOf);
		BigDecimal statement = movement.add(openingBalance == null ? BigDecimal.ZERO : openingBalance);
		Map<String, Object> unmatchedLines = repository.unmatchedStatementLines(companyId, bankAccountId, asOf);
		Map<String, Object> unmatchedPayments = repository.unmatchedPayments(companyId, bankAccountId, asOf);
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
