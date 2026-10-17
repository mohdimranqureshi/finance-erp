package com.financeerp.company;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public CompanyController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record CreateCompanyRequest(@NotBlank String name, String legalName, String baseCurrency) {
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@Valid @RequestBody CreateCompanyRequest req) {
		String currency = req.baseCurrency() == null || req.baseCurrency().isBlank() ? "INR"
				: req.baseCurrency().toUpperCase();
		if (!currency.matches("[A-Z]{3}"))
			throw new IllegalArgumentException("baseCurrency must be a 3-letter currency code.");
		long userId = access.currentUserId();
		Map<String, Object> company = jdbc.queryForMap(
				"INSERT INTO companies(name,legal_name,base_currency) VALUES (?,?,?) RETURNING id,name,legal_name AS \"legalName\",base_currency AS \"baseCurrency\",status,created_at AS \"createdAt\"",
				req.name().trim(), req.legalName(), currency);
		long companyId = ((Number) company.get("id")).longValue();
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?, 'OWNER')", companyId,
				userId);
		LocalDate today = LocalDate.now();
		int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
		LocalDate start = LocalDate.of(startYear, 4, 1);
		LocalDate end = LocalDate.of(startYear + 1, 3, 31);
		jdbc.update("INSERT INTO accounting_periods(company_id,name,start_date,end_date) VALUES (?,?,?,?)", companyId,
				"FY" + startYear + "-" + (startYear + 1), start, end);
		return company;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		String email = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
				.getName();
		return jdbc.queryForList(
				"SELECT c.id,c.name,c.legal_name AS \"legalName\",c.base_currency AS \"baseCurrency\",c.status,c.created_at AS \"createdAt\" FROM companies c JOIN company_memberships m ON m.company_id=c.id JOIN app_users u ON u.id=m.user_id WHERE lower(u.email)=lower(?) AND u.active=true ORDER BY c.id DESC",
				email);
	}
}
