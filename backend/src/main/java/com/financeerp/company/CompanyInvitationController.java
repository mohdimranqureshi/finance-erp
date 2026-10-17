package com.financeerp.company;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CompanyInvitationController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final org.springframework.security.crypto.password.PasswordEncoder encoder;
	private final SecureRandom random = new SecureRandom();

	public CompanyInvitationController(JdbcTemplate jdbc, CompanyAccess access,
			org.springframework.security.crypto.password.PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.access = access;
		this.encoder = encoder;
	}

	public record CreateInvitationRequest(@Email @NotBlank @Size(max = 254) String email, @NotNull Role role) {
	}

	public enum Role {
		ACCOUNTANT, APPROVER, VIEWER
	}

	public record AcceptInvitationRequest(@NotBlank @Size(max = 100) String token,
			@NotBlank @Size(min = 12, max = 72) String password, @NotBlank @Size(max = 180) String displayName) {
	}

	@PostMapping("/companies/{companyId}/invitations")
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId,
			@Valid @RequestBody CreateInvitationRequest request) {
		long actor = access.requireMembership(companyId);
		Integer isOwner = jdbc.queryForObject(
				"SELECT count(*) FROM company_memberships WHERE company_id=? AND user_id=? AND role='OWNER'",
				Integer.class, companyId, actor);
		if (isOwner == null || isOwner == 0)
			throw new org.springframework.security.access.AccessDeniedException(
					"Only the company owner can invite members.");
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		Integer existing = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",
				Integer.class, email);
		if (existing != null && existing > 0)
			throw new IllegalArgumentException(
					"This email already has an account. Add the existing user through Company Team instead.");
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String hash = sha256(token);
		OffsetDateTime expiry = OffsetDateTime.now().plusHours(48);
		jdbc.update(
				"DELETE FROM company_invitations WHERE company_id=? AND lower(email)=lower(?) AND accepted_at IS NULL AND expires_at<=now()",
				companyId, email);
		try {
			jdbc.update(
					"INSERT INTO company_invitations(company_id,email,role,token_hash,expires_at,created_by_user_id) VALUES (?,?,?,?,?,?)",
					companyId, email, request.role().name(), hash, expiry, actor);
		} catch (DuplicateKeyException ex) {
			throw new IllegalArgumentException(
					"A pending invitation already exists for this email. Use it or wait until it expires.");
		}
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?, 'MEMBER_INVITED','COMPANY_INVITATION',?,jsonb_build_object('email',?,'role',?))",
				companyId, email, hash.substring(0, 12), email, request.role().name());
		// The token is shown once. Deliver it over a trusted channel; do not log it or
		// email it without a configured provider.
		return Map.of("email", email, "role", request.role().name(), "invitationToken", token, "expiresAt", expiry,
				"deliveryMode", "MANUAL_SECURE_CHANNEL");
	}

	@PostMapping("/auth/accept-invitation")
	@Transactional
	public Map<String, Object> accept(@Valid @RequestBody AcceptInvitationRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes for BCrypt compatibility.");
		String hash = sha256(request.token());
		var invitations = jdbc.query(
				"SELECT id,company_id,email,role,expires_at FROM company_invitations WHERE token_hash=? AND accepted_at IS NULL FOR UPDATE",
				(rs, row) -> Map.<String, Object>of("id", rs.getLong("id"), "companyId", rs.getLong("company_id"),
						"email", rs.getString("email"), "role", rs.getString("role"), "expiresAt",
						rs.getObject("expires_at", OffsetDateTime.class)),
				hash);
		if (invitations.isEmpty())
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.BAD_REQUEST, "Invitation token is invalid or already used.");
		Map<String, Object> invitation = invitations.get(0);
		if (((OffsetDateTime) invitation.get("expiresAt")).isBefore(OffsetDateTime.now()))
			throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,
					"Invitation has expired. Ask the company owner to create a new one.");
		String email = (String) invitation.get("email");
		Integer exists = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",
				Integer.class, email);
		if (exists != null && exists > 0)
			throw new IllegalArgumentException(
					"This email already has an account. Sign in and ask the owner to add your account to the company.");
		Long userId = jdbc.queryForObject(
				"INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?) RETURNING id", Long.class,
				email, encoder.encode(request.password()), request.displayName().trim());
		long companyId = ((Number) invitation.get("companyId")).longValue();
		jdbc.update("INSERT INTO company_memberships(company_id,user_id,role) VALUES (?,?,?)", companyId, userId,
				invitation.get("role"));
		jdbc.update("UPDATE company_invitations SET accepted_at=now(),accepted_by_user_id=? WHERE id=?", userId,
				invitation.get("id"));
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (?,?, 'MEMBER_INVITATION_ACCEPTED','COMPANY_INVITATION',?,jsonb_build_object('email',?,'role',?))",
				companyId, email, String.valueOf(invitation.get("id")), email, invitation.get("role"));
		return Map.of("created", true, "email", email, "companyId", companyId, "role", invitation.get("role"),
				"message", "Account created. Sign in with your new credentials.");
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is unavailable.", ex);
		}
	}
}
