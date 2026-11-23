package com.financeerp.service;

import com.financeerp.dto.CompanyDto;
import com.financeerp.repository.CompanyRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CompanyService {
	private final CompanyRepository companies;
	private final CompanyAccess access;

	public CompanyService(CompanyRepository companies, CompanyAccess access) {
		this.companies = companies;
		this.access = access;
	}

	@Transactional
	public Map<String, Object> create(CompanyDto.CreateCompanyRequest req) {
		String currency = req.baseCurrency() == null || req.baseCurrency().isBlank() ? "INR"
				: req.baseCurrency().toUpperCase();
		if (!currency.matches("[A-Z]{3}"))
			throw new IllegalArgumentException("baseCurrency must be a 3-letter currency code.");
		long userId = access.currentUserId();
		Map<String, Object> company = companies.insertCompany(req.name().trim(), req.legalName(), currency);
		long companyId = ((Number) company.get("id")).longValue();
		companies.insertOwnerMembership(companyId, userId);
		LocalDate today = LocalDate.now();
		int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
		LocalDate start = LocalDate.of(startYear, 4, 1);
		LocalDate end = LocalDate.of(startYear + 1, 3, 31);
		companies.insertAccountingPeriod(companyId, "FY" + startYear + "-" + (startYear + 1), start, end);
		return company;
	}

	public List<Map<String, Object>> list() {
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		return companies.findByMemberEmail(email);
	}

	public Map<String, Object> settings(long companyId) {
		access.requireMembership(companyId);
		return companies.findSettings(companyId);
	}

	/** Turning self-approval on removes separation of duties, so only an owner may change it. */
	@Transactional
	public Map<String, Object> updateSettings(long companyId, CompanyDto.SettingsRequest req) {
		access.requireRole(companyId, Set.of("OWNER"));
		companies.updateAllowSelfApproval(companyId, req.allowSelfApproval());
		companies.insertSettingsAuditEvent(companyId,
				SecurityContextHolder.getContext().getAuthentication().getName(),
				Boolean.TRUE.equals(req.allowSelfApproval()) ? "SELF_APPROVAL_ENABLED" : "SELF_APPROVAL_DISABLED",
				req.allowSelfApproval());
		return settings(companyId);
	}
}
