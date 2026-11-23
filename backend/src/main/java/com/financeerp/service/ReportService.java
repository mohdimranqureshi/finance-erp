package com.financeerp.service;

import com.financeerp.repository.ReportRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportService {
	private static final Set<String> CREDIT_NORMAL = Set.of("LIABILITY", "EQUITY", "INCOME");

	private final ReportRepository reports;
	private final CompanyAccess access;

	public ReportService(ReportRepository reports, CompanyAccess access) {
		this.reports = reports;
		this.access = access;
	}

	public List<Map<String, Object>> trialBalance(long companyId, LocalDate from, LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		return reports.findTrialBalance(companyId, from, to);
	}

	/** Rows with an "amount" in the account's natural sign (credit-positive when creditNormal). */
	private List<Map<String, Object>> balances(long companyId, String type, LocalDate from, LocalDate to,
			boolean creditNormal, boolean includeClosing) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> r : reports.findBalanceRows(companyId, type, from, to, includeClosing)) {
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

	/** Account ledger for a period: opening balance, each posted line, and a running balance. */
	public Map<String, Object> ledger(long companyId, long accountId, LocalDate from, LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		Map<String, Object> account = reports.findAccount(companyId, accountId)
				.orElseThrow(() -> new IllegalArgumentException("Account not found in this company."));
		BigDecimal opening = reports.findOpeningDebitMinusCredit(companyId, accountId, from);
		List<Map<String, Object>> rows = reports.findLedgerRows(companyId, accountId, from, to);

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

	public Map<String, Object> profitAndLoss(long companyId, LocalDate from, LocalDate to) {
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
	public Map<String, Object> balanceSheet(long companyId, LocalDate asOf) {
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
	public Map<String, Object> gstSummary(long companyId, LocalDate from, LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		Map<String, Object> outward = reports.findOutwardSupplies(companyId, from, to);
		Map<String, Object> itc = reports.findEligibleItc(companyId, from, to);
		Map<String, Object> creditNotes = reports.findSalesCreditNotes(companyId, from, to);
		Map<String, Object> debitNotes = reports.findPurchaseDebitNotes(companyId, from, to);
		outward = subtractHeads(outward, creditNotes);
		itc = subtractHeads(itc, debitNotes);
		Map<String, Object> rcm = reports.findReverseCharge(companyId, from, to);
		BigDecimal c = ((BigDecimal) outward.get("cgst")).add((BigDecimal) rcm.get("cgst")).subtract((BigDecimal) itc.get("cgst"));
		BigDecimal s = ((BigDecimal) outward.get("sgst")).add((BigDecimal) rcm.get("sgst")).subtract((BigDecimal) itc.get("sgst"));
		BigDecimal i = ((BigDecimal) outward.get("igst")).add((BigDecimal) rcm.get("igst")).subtract((BigDecimal) itc.get("igst"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("outwardSupplies", outward);
		out.put("reverseChargeLiability", rcm);
		out.put("eligibleItc", itc);
		out.put("netByHead", Map.of("cgst", c, "sgst", s, "igst", i, "total", c.add(s).add(i)));
		out.put("note", "Indicative summary only. See /gst/gstr3b for cross-head ITC set-off and the cash needed for reverse charge. Blocked credits, Rule 42/43 and return filing are not implemented.");
		return out;
	}

	private static Map<String, Object> subtractHeads(Map<String, Object> base, Map<String, Object> minus) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String k : List.of("taxableValue", "cgst", "sgst", "igst"))
			out.put(k, ((BigDecimal) base.get(k)).subtract((BigDecimal) minus.get(k)));
		return out;
	}
}
