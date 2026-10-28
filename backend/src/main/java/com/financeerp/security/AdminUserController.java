package com.financeerp.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin-only account administration. The ADMIN role is enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public AdminUserController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	public record StatusRequest(@NotNull Boolean active) {
	}

	/** Table, column and human label for every accounting reference that must outlive an account. */
	private static final String[][] LEDGER_REFS = { { "journal_entries", "created_by_user_id", "journal(s) created" },
			{ "journal_entries", "submitted_by_user_id", "journal submission(s)" },
			{ "journal_entries", "approved_by_user_id", "journal approval(s)" },
			{ "journal_entries", "posted_by_user_id", "posted journal(s)" },
			{ "sales_invoices", "created_by_user_id", "sales invoice(s)" },
			{ "purchase_bills", "created_by_user_id", "purchase bill(s)" },
			{ "payments", "created_by_user_id", "payment(s)" },
			{ "adjustment_notes", "created_by_user_id", "credit/debit note(s)" },
			{ "revenue_schedules", "created_by_user_id", "revenue schedule(s)" },
			{ "ai_proposals", "created_by_user_id", "AI proposal(s)" },
			{ "ai_proposals", "decided_by_user_id", "AI proposal decision(s)" },
			{ "company_invitations", "created_by_user_id", "invitation(s) created" },
			{ "company_invitations", "accepted_by_user_id", "accepted invitation(s)" },
			{ "accounting_periods", "closed_by", "closed period(s)" },
			{ "bank_statement_lines", "matched_by_user_id", "bank reconciliation match(es)" } };

	@GetMapping
	public List<Map<String, Object>> list() {
		return jdbc.queryForList(
				"SELECT u.id,u.email,u.display_name AS \"displayName\",u.active,u.is_admin AS \"admin\",u.created_at AS \"createdAt\","
						+ "(SELECT count(*) FROM company_memberships m WHERE m.user_id=u.id) AS \"companyCount\" "
						+ "FROM app_users u ORDER BY u.id");
	}

	@PutMapping("/{userId}/status")
	@Transactional
	public Map<String, Object> setStatus(@PathVariable long userId, @Valid @RequestBody StatusRequest request) {
		Map<String, Object> user = load(userId);
		boolean active = Boolean.TRUE.equals(request.active());
		if (userId == access.currentUserId())
			throw new IllegalArgumentException("You cannot change your own account status.");
		if (!active)
			requireAnotherAdminRemains(user);
		jdbc.update("UPDATE app_users SET active=? WHERE id=?", active, userId);
		audit(active ? "USER_REACTIVATED" : "USER_DEACTIVATED", userId, String.valueOf(user.get("email")));
		return load(userId);
	}

	/** Deletes only accounts with no accounting footprint; everyone else must be deactivated. */
	@DeleteMapping("/{userId}")
	@Transactional
	public Map<String, Object> delete(@PathVariable long userId) {
		Map<String, Object> user = load(userId);
		if (userId == access.currentUserId())
			throw new IllegalArgumentException("You cannot delete your own account.");
		requireAnotherAdminRemains(user);
		List<String> blocking = new ArrayList<>();
		for (String[] ref : LEDGER_REFS) {
			Integer count = jdbc.queryForObject("SELECT count(*) FROM " + ref[0] + " WHERE " + ref[1] + "=?",
					Integer.class, userId);
			if (count != null && count > 0)
				blocking.add(count + " " + ref[2]);
		}
		if (!blocking.isEmpty())
			throw new IllegalArgumentException("This account is referenced by " + String.join(", ", blocking)
					+ ". Deactivate it instead so the accounting records and audit trail stay intact.");
		jdbc.update("DELETE FROM company_memberships WHERE user_id=?", userId);
		jdbc.update("DELETE FROM app_users WHERE id=?", userId);
		audit("USER_DELETED", userId, String.valueOf(user.get("email")));
		return Map.of("deleted", true, "email", user.get("email"));
	}

	private Map<String, Object> load(long userId) {
		try {
			return jdbc.queryForMap(
					"SELECT id,email,display_name AS \"displayName\",active,is_admin AS \"admin\",created_at AS \"createdAt\" FROM app_users WHERE id=?",
					userId);
		} catch (EmptyResultDataAccessException ex) {
			throw new IllegalArgumentException("User not found.");
		}
	}

	private void requireAnotherAdminRemains(Map<String, Object> user) {
		if (!Boolean.TRUE.equals(user.get("admin")))
			return;
		Integer others = jdbc.queryForObject(
				"SELECT count(*) FROM app_users WHERE is_admin=true AND active=true AND id<>?", Integer.class,
				((Number) user.get("id")).longValue());
		if (others == null || others == 0)
			throw new IllegalArgumentException(
					"This is the last active administrator. Make another account an administrator first.");
	}

	private void audit(String action, long userId, String email) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,?,'USER',?,jsonb_build_object('email',?))",
				SecurityContextHolder.getContext().getAuthentication().getName(), action, String.valueOf(userId),
				email);
	}
}
