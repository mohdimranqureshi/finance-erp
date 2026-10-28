package com.financeerp.report;

import com.financeerp.security.CompanyAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/reports")
public class ReportController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public ReportController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@GetMapping("/trial-balance")
	public List<Map<String, Object>> trialBalance(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		return jdbc.queryForList("""
				SELECT a.id AS "accountId", a.code, a.name, a.account_type AS "accountType",
				       COALESCE(SUM(l.debit),0) AS "debitTotal",
				       COALESCE(SUM(l.credit),0) AS "creditTotal",
				       COALESCE(SUM(l.debit-l.credit),0) AS "netDebit"
				FROM accounts a
				LEFT JOIN (
				    SELECT jl.company_id, jl.account_id, jl.debit, jl.credit
				    FROM journal_lines jl
				    JOIN journal_entries je ON je.company_id=jl.company_id AND je.id=jl.journal_entry_id
				    WHERE je.status='POSTED' AND je.journal_date BETWEEN ? AND ?
				) l ON l.company_id=a.company_id AND l.account_id=a.id
				WHERE a.company_id=?
				GROUP BY a.id,a.code,a.name,a.account_type ORDER BY a.code
				""", from, to, companyId);
	}

	private static final String BALANCES = """
			SELECT a.id AS "accountId", a.code, a.name, COALESCE(SUM(l.credit-l.debit),0) AS "netCredit"
			FROM accounts a
			JOIN journal_lines l ON l.company_id=a.company_id AND l.account_id=a.id
			JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
			WHERE a.company_id=? AND je.status='POSTED' AND a.account_type=? AND je.journal_date BETWEEN ? AND ?
			  AND (? OR NOT je.is_closing)
			GROUP BY a.id,a.code,a.name ORDER BY a.code
			""";

	/** Rows with an "amount" in the account's natural sign (credit-positive when creditNormal). */
	private List<Map<String, Object>> balances(long companyId, String type, LocalDate from, LocalDate to,
			boolean creditNormal, boolean includeClosing) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> r : jdbc.queryForList(BALANCES, companyId, type, from, to, includeClosing)) {
			BigDecimal net = (BigDecimal) r.get("netCredit");
			Map<String, Object> row = new LinkedHashMap<>(r);
			row.remove("netCredit");
			row.put("amount", creditNormal ? net : net.negate());
			rows.add(row);
		}
		return rows;
	}

	private static BigDecimal sum(List<Map<String, Object>> rows) {
		return rows.stream().map(r -> (BigDecimal) r.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static final java.util.Set<String> CREDIT_NORMAL = java.util.Set.of("LIABILITY", "EQUITY", "INCOME");

	/** Account ledger for a period: opening balance, each posted line, and a running balance. */
	@GetMapping("/ledger")
	public Map<String, Object> ledger(@PathVariable long companyId, @RequestParam long accountId,
			@RequestParam LocalDate from, @RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		Map<String, Object> account;
		try {
			account = jdbc.queryForMap(
					"SELECT id,code,name,account_type AS \"accountType\" FROM accounts WHERE company_id=? AND id=?",
					companyId, accountId);
		} catch (org.springframework.dao.EmptyResultDataAccessException ex) {
			throw new IllegalArgumentException("Account not found in this company.");
		}
		BigDecimal opening = jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l
				JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				WHERE l.company_id=? AND l.account_id=? AND je.status='POSTED' AND je.journal_date < ?
				""", BigDecimal.class, companyId, accountId, from);
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT je.id AS "journalId", je.journal_date AS "date", je.description,
				       l.description AS "lineDescription", l.debit, l.credit
				FROM journal_lines l
				JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id
				WHERE l.company_id=? AND l.account_id=? AND je.status='POSTED' AND je.journal_date BETWEEN ? AND ?
				ORDER BY je.journal_date, je.id, l.id
				""", companyId, accountId, from, to);

		boolean creditNormal = CREDIT_NORMAL.contains(String.valueOf(account.get("accountType")));
		BigDecimal running = opening == null ? BigDecimal.ZERO : opening;
		BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
		List<Map<String, Object>> lines = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			BigDecimal debit = (BigDecimal) r.get("debit"), credit = (BigDecimal) r.get("credit");
			debits = debits.add(debit);
			credits = credits.add(credit);
			running = running.add(debit).subtract(credit);
			Map<String, Object> line = new LinkedHashMap<>(r);
			line.put("balance", creditNormal ? running.negate() : running);
			lines.add(line);
		}
		BigDecimal openingNatural = opening == null ? BigDecimal.ZERO : opening;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("accountId", account.get("id"));
		out.put("code", account.get("code"));
		out.put("name", account.get("name"));
		out.put("accountType", account.get("accountType"));
		out.put("normalBalance", creditNormal ? "CREDIT" : "DEBIT");
		out.put("from", from);
		out.put("to", to);
		out.put("openingBalance", creditNormal ? openingNatural.negate() : openingNatural);
		out.put("lines", lines);
		out.put("totalDebits", debits);
		out.put("totalCredits", credits);
		out.put("closingBalance", creditNormal ? running.negate() : running);
		return out;
	}

	@GetMapping("/profit-and-loss")
	public Map<String, Object> profitAndLoss(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		List<Map<String, Object>> income = balances(companyId, "INCOME", from, to, true, false);
		List<Map<String, Object>> expenses = balances(companyId, "EXPENSE", from, to, false, false);
		BigDecimal totalIncome = sum(income), totalExpenses = sum(expenses);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("income", income);
		out.put("expenses", expenses);
		out.put("totalIncome", totalIncome);
		out.put("totalExpenses", totalExpenses);
		out.put("netProfit", totalIncome.subtract(totalExpenses));
		return out;
	}

	/** No year-end closing entries exist yet, so cumulative profit is shown as a separate equity line. */
	@GetMapping("/balance-sheet")
	public Map<String, Object> balanceSheet(@PathVariable long companyId, @RequestParam LocalDate asOf) {
		access.requireMembership(companyId);
		LocalDate start = LocalDate.of(1900, 1, 1);
		List<Map<String, Object>> assets = balances(companyId, "ASSET", start, asOf, false, true);
		List<Map<String, Object>> liabilities = balances(companyId, "LIABILITY", start, asOf, true, true);
		List<Map<String, Object>> equity = balances(companyId, "EQUITY", start, asOf, true, true);
		BigDecimal profit = sum(balances(companyId, "INCOME", start, asOf, true, true))
				.subtract(sum(balances(companyId, "EXPENSE", start, asOf, false, true)));
		BigDecimal totalAssets = sum(assets), totalLiabilities = sum(liabilities),
				totalEquity = sum(equity).add(profit);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("asOf", asOf);
		out.put("assets", assets);
		out.put("liabilities", liabilities);
		out.put("equity", equity);
		out.put("accumulatedProfit", profit);
		out.put("totalAssets", totalAssets);
		out.put("totalLiabilities", totalLiabilities);
		out.put("totalEquity", totalEquity);
		out.put("balanced", totalAssets.compareTo(totalLiabilities.add(totalEquity)) == 0);
		return out;
	}

	/** Outward supplies and eligible ITC from invoices/bills whose journals are posted and not reversed. */
	@GetMapping("/gst-summary")
	public Map<String, Object> gstSummary(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		Map<String, Object> outward = jdbc.queryForMap("""
				SELECT COALESCE(SUM(i.taxable_amount),0) AS "taxableValue", COALESCE(SUM(i.cgst_amount),0) AS cgst,
				       COALESCE(SUM(i.sgst_amount),0) AS sgst, COALESCE(SUM(i.igst_amount),0) AS igst
				FROM sales_invoices i
				JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
		Map<String, Object> itc = jdbc.queryForMap("""
				SELECT COALESCE(SUM(b.taxable_amount),0) AS "taxableValue", COALESCE(SUM(b.cgst_amount),0) AS cgst,
				       COALESCE(SUM(b.sgst_amount),0) AS sgst, COALESCE(SUM(b.igst_amount),0) AS igst
				FROM purchase_bills b
				JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE b.company_id=? AND b.bill_date BETWEEN ? AND ? AND b.itc_eligible
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
		Map<String, Object> creditNotes = jdbc.queryForMap("""
				SELECT COALESCE(SUM(n.taxable_amount),0) AS "taxableValue", COALESCE(SUM(n.cgst_amount),0) AS cgst,
				       COALESCE(SUM(n.sgst_amount),0) AS sgst, COALESCE(SUM(n.igst_amount),0) AS igst
				FROM adjustment_notes n
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
		Map<String, Object> debitNotes = jdbc.queryForMap("""
				SELECT COALESCE(SUM(n.taxable_amount),0) AS "taxableValue", COALESCE(SUM(n.cgst_amount),0) AS cgst,
				       COALESCE(SUM(n.sgst_amount),0) AS sgst, COALESCE(SUM(n.igst_amount),0) AS igst
				FROM adjustment_notes n
				JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id AND b.itc_eligible
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				""", companyId, from, to);
		outward = subtractHeads(outward, creditNotes);
		itc = subtractHeads(itc, debitNotes);
		BigDecimal c = ((BigDecimal) outward.get("cgst")).subtract((BigDecimal) itc.get("cgst"));
		BigDecimal s = ((BigDecimal) outward.get("sgst")).subtract((BigDecimal) itc.get("sgst"));
		BigDecimal i = ((BigDecimal) outward.get("igst")).subtract((BigDecimal) itc.get("igst"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("outwardSupplies", outward);
		out.put("eligibleItc", itc);
		out.put("netByHead", Map.of("cgst", c, "sgst", s, "igst", i, "total", c.add(s).add(i)));
		out.put("note", "Indicative summary only. Cross-head ITC set-off, blocked credits, RCM, Rule 42/43 and return filing are not implemented.");
		return out;
	}

	private static Map<String, Object> subtractHeads(Map<String, Object> base, Map<String, Object> minus) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String k : List.of("taxableValue", "cgst", "sgst", "igst"))
			out.put(k, ((BigDecimal) base.get(k)).subtract((BigDecimal) minus.get(k)));
		return out;
	}
}
