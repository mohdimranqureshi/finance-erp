package com.financeerp.service;

import com.financeerp.dto.AccountDto;
import com.financeerp.repository.AccountRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AccountService {
	private final AccountRepository accounts;
	private final CompanyAccess access;

	public AccountService(AccountRepository accounts, CompanyAccess access) {
		this.accounts = accounts;
		this.access = access;
	}

	public Map<String, Object> create(long companyId, AccountDto.CreateAccountRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		if (req.parentAccountId() != null) {
			if (accounts.countByCompanyAndId(companyId, req.parentAccountId()) == 0)
				throw new IllegalArgumentException("Parent account does not belong to this company.");
		}
		return accounts.insertAccount(companyId, req.code().trim(), req.name().trim(), req.accountType().name(),
				req.parentAccountId());
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return accounts.findByCompany(companyId);
	}
}
