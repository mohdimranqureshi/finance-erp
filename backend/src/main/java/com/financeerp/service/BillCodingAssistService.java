package com.financeerp.service;

import com.financeerp.dto.BillCodingAssistDto;
import com.financeerp.repository.BillCodingAssistRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BillCodingAssistService {
	private record Rule(List<String> keywords, String accountCode, String tdsSection, Integer tdsRate, String reason) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(List.of("rent", "lease", "office space"), "5200", "194I", 10, "Looks like a rent or lease charge."),
			new Rule(List.of("consult", "legal", "audit", "advisory", "professional", "chartered"), "5300", "194J", 10,
					"Looks like professional or technical fees."),
			new Rule(List.of("salary", "payroll", "wages", "bonus"), "5100", null, null,
					"Looks like an employee cost; payroll TDS (192) is handled separately."),
			new Rule(List.of("commission", "brokerage"), "5900", "194H", 2, "Looks like commission or brokerage."),
			new Rule(List.of("contractor", "labour", "fabrication", "housekeeping", "security services"), "5000",
					"194C", 1, "Looks like a contract work payment."),
			new Rule(List.of("depreciation", "amortisation", "amortization"), "5400", null, null,
					"Looks like a non-cash depreciation entry."));

	private final BillCodingAssistRepository repo;
	private final CompanyAccess access;

	public BillCodingAssistService(BillCodingAssistRepository repo, CompanyAccess access) {
		this.repo = repo;
		this.access = access;
	}

	public Map<String, Object> suggest(long companyId, BillCodingAssistDto.SuggestRequest req) {
		access.requireMembership(companyId);
		String text = (req.description() + " " + (req.vendorName() == null ? "" : req.vendorName())).toLowerCase();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", "KEYWORD_RULES");
		out.put("requiresHumanReview", true);
		for (Rule rule : RULES) {
			if (rule.keywords().stream().noneMatch(text::contains))
				continue;
			List<Map<String, Object>> account = repo.findActiveExpenseAccounts(companyId, rule.accountCode());
			out.put("matched", true);
			out.put("reason", rule.reason());
			out.put("expenseAccount", account.isEmpty() ? null : account.get(0));
			out.put("tdsSection", rule.tdsSection());
			out.put("indicativeTdsRate", rule.tdsRate());
			out.put("caveat", "Indicative only. TDS applicability, thresholds and rates depend on the payee and current law.");
			return out;
		}
		out.put("matched", false);
		out.put("reason", "No rule matched; code this bill manually.");
		return out;
	}
}
