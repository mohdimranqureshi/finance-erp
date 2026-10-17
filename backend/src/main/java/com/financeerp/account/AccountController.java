package com.financeerp.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import com.financeerp.security.CompanyAccess;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/accounts")
public class AccountController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public AccountController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public enum AccountType {
		ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
	}

	public record CreateAccountRequest(@NotBlank String code, @NotBlank String name, @NotNull AccountType accountType,
			Long parentAccountId) {
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateAccountRequest req) {
		access.requireRole(companyId, java.util.Set.of("OWNER", "ACCOUNTANT"));
		if (req.parentAccountId() != null) {
			Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=?", Integer.class,
					companyId, req.parentAccountId());
			if (n == null || n == 0)
				throw new IllegalArgumentException("Parent account does not belong to this company.");
		}
		return jdbc.queryForMap(
				"INSERT INTO accounts(company_id,code,name,account_type,parent_account_id) VALUES (?,?,?,?,?) RETURNING id,company_id AS \"companyId\",code,name,account_type AS \"accountType\",parent_account_id AS \"parentAccountId\",active",
				companyId, req.code().trim(), req.name().trim(), req.accountType().name(), req.parentAccountId());
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",code,name,account_type AS \"accountType\",parent_account_id AS \"parentAccountId\",active FROM accounts WHERE company_id=? ORDER BY code",
				companyId);
	}

	private void requireCompany(long id) {
		access.requireMembership(id);
	}
}
