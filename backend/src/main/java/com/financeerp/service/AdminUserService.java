package com.financeerp.service;

import com.financeerp.dto.AdminUserDto;
import com.financeerp.repository.UserRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin-only account administration. The ADMIN role is enforced in SecurityConfig. */
@Service
public class AdminUserService {
	private final UserRepository users;
	private final CompanyAccess access;
	private final MfaService mfa;

	public AdminUserService(UserRepository users, CompanyAccess access, MfaService mfa) {
		this.users = users;
		this.access = access;
		this.mfa = mfa;
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

	public List<Map<String, Object>> list() {
		return users.listUsers();
	}

	@Transactional
	public Map<String, Object> setStatus(long userId, AdminUserDto.StatusRequest request) {
		return changeStatus(userId, Boolean.TRUE.equals(request.active()));
	}

	@Transactional
	public Map<String, Object> disable(long userId) {
		return changeStatus(userId, false);
	}

	@Transactional
	public Map<String, Object> enable(long userId) {
		return changeStatus(userId, true);
	}

	/** For a lost authenticator: removes TOTP and recovery codes and signs the user out everywhere. */
	@Transactional
	public Map<String, Object> resetMfa(long userId) {
		Map<String, Object> user = load(userId);
		if (userId == access.currentUserId())
			throw new IllegalArgumentException(
					"You cannot reset your own two-factor authentication. Use your security settings instead.");
		mfa.reset(userId);
		audit("MFA_RESET", userId, String.valueOf(user.get("email")));
		return load(userId);
	}

	/** Disabling also bumps session_version, so the user's open browser sessions stop working immediately. */
	private Map<String, Object> changeStatus(long userId, boolean active) {
		Map<String, Object> user = load(userId);
		if (userId == access.currentUserId())
			throw new IllegalArgumentException("You cannot change your own account status.");
		if (!active)
			requireAnotherAdminRemains(user);
		users.updateActive(userId, active);
		audit(active ? "USER_ENABLED" : "USER_DISABLED", userId, String.valueOf(user.get("email")));
		return load(userId);
	}

	/** Deletes only accounts with no accounting footprint; everyone else must be deactivated. */
	@Transactional
	public Map<String, Object> delete(long userId) {
		Map<String, Object> user = load(userId);
		if (userId == access.currentUserId())
			throw new IllegalArgumentException("You cannot delete your own account.");
		requireAnotherAdminRemains(user);
		List<String> blocking = new ArrayList<>();
		for (String[] ref : LEDGER_REFS) {
			Integer count = users.countUserReferences(ref[0], ref[1], userId);
			if (count != null && count > 0)
				blocking.add(count + " " + ref[2]);
		}
		if (!blocking.isEmpty())
			throw new IllegalArgumentException("This account is referenced by " + String.join(", ", blocking)
					+ ". Deactivate it instead so the accounting records and audit trail stay intact.");
		users.deleteMemberships(userId);
		users.deleteUser(userId);
		audit("USER_DELETED", userId, String.valueOf(user.get("email")));
		return Map.of("deleted", true, "email", user.get("email"));
	}

	private Map<String, Object> load(long userId) {
		Map<String, Object> user = users.findUserById(userId);
		if (user == null)
			throw new IllegalArgumentException("User not found.");
		return user;
	}

	private void requireAnotherAdminRemains(Map<String, Object> user) {
		if (!Boolean.TRUE.equals(user.get("admin")))
			return;
		Integer others = users.countOtherActiveAdmins(((Number) user.get("id")).longValue());
		if (others == null || others == 0)
			throw new IllegalArgumentException(
					"This is the last active administrator. Make another account an administrator first.");
	}

	private void audit(String action, long userId, String email) {
		users.insertUserEmailAudit(SecurityContextHolder.getContext().getAuthentication().getName(), action,
				String.valueOf(userId), email);
	}
}
