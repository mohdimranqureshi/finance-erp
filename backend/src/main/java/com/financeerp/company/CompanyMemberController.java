package com.financeerp.company;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/members")
public class CompanyMemberController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public CompanyMemberController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record AddMemberRequest(@Email @NotBlank String email, @NotNull Role role) {
	}

	public enum Role {
		ACCOUNTANT, APPROVER, VIEWER
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		long actorId = access.requireMembership(companyId);
		requireOwner(companyId, actorId);
		return jdbc.queryForList(
				"SELECT u.id AS \"userId\",u.email,u.display_name AS \"displayName\",m.role,m.created_at AS \"joinedAt\" FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? ORDER BY m.created_at",
				companyId);
	}

	@PostMapping
	@Transactional
	public Map<String, Object> add(@PathVariable long companyId, @Valid @RequestBody AddMemberRequest request) {
		long actorId = access.requireMembership(companyId);
		requireOwner(companyId, actorId);
		List<Map<String, Object>> users = jdbc.queryForList(
				"SELECT id,email,display_name AS \"displayName\" FROM app_users WHERE lower(email)=lower(?) AND active=true",
				request.email().trim());
		if (users.isEmpty())
			throw new IllegalArgumentException("No active user exists for that email. Ask them to register first.");
		long userId = ((Number) users.get(0).get("id")).longValue();
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?,?)", companyId, userId,
				request.role().name());
		return jdbc.queryForMap(
				"SELECT u.id AS \"userId\",u.email,u.display_name AS \"displayName\",m.role,m.created_at AS \"joinedAt\" FROM company_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.company_id=? AND m.user_id=?",
				companyId, userId);
	}

	private void requireOwner(long companyId, long userId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role='OWNER'",
				Integer.class, companyId, userId);
		if (count == null || count == 0)
			throw new org.springframework.security.access.AccessDeniedException(
					"Only the company owner can manage members.");
	}
}
