package com.financeerp.service;

import com.financeerp.dto.CompanyInvitationDto;
import com.financeerp.repository.CompanyInvitationRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CompanyInvitationService {
	private final CompanyInvitationRepository invitations;
	private final CompanyAccess access;
	private final PasswordEncoder encoder;
	private final SecureRandom random = new SecureRandom();

	public CompanyInvitationService(CompanyInvitationRepository invitations, CompanyAccess access,
			PasswordEncoder encoder) {
		this.invitations = invitations;
		this.access = access;
		this.encoder = encoder;
	}

	@Transactional
	public Map<String, Object> create(long companyId, CompanyInvitationDto.CreateInvitationRequest request) {
		long actor = access.requireMembership(companyId);
		if (invitations.countOwnerMemberships(companyId, actor) == 0)
			throw new AccessDeniedException("Only the company owner can invite members.");
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (invitations.countUsersByEmail(email) > 0)
			throw new IllegalArgumentException(
					"This email already has an account. Add the existing user through Company Team instead.");
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String hash = sha256(token);
		OffsetDateTime expiry = OffsetDateTime.now().plusHours(48);
		invitations.deleteExpiredPending(companyId, email);
		try {
			invitations.insertInvitation(companyId, email, request.role().name(), hash, expiry, actor);
		} catch (DuplicateKeyException ex) {
			throw new IllegalArgumentException(
					"A pending invitation already exists for this email. Use it or wait until it expires.");
		}
		invitations.insertInvitedAuditEvent(companyId, email, hash.substring(0, 12), request.role().name());
		// The token is shown once. Deliver it over a trusted channel; do not log it or
		// email it without a configured provider.
		return Map.of("email", email, "role", request.role().name(), "invitationToken", token, "expiresAt", expiry,
				"deliveryMode", "MANUAL_SECURE_CHANNEL");
	}

	@Transactional
	public Map<String, Object> accept(CompanyInvitationDto.AcceptInvitationRequest request) {
		if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes for BCrypt compatibility.");
		String hash = sha256(request.token());
		List<Map<String, Object>> pending = invitations.lockPendingByTokenHash(hash);
		if (pending.isEmpty())
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.BAD_REQUEST, "Invitation token is invalid or already used.");
		Map<String, Object> invitation = pending.get(0);
		if (((OffsetDateTime) invitation.get("expiresAt")).isBefore(OffsetDateTime.now()))
			throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,
					"Invitation has expired. Ask the company owner to create a new one.");
		String email = (String) invitation.get("email");
		if (invitations.countUsersByEmail(email) > 0)
			throw new IllegalArgumentException(
					"This email already has an account. Sign in and ask the owner to add your account to the company.");
		Long userId = invitations.insertUser(email, encoder.encode(request.password()),
				request.displayName().trim());
		long companyId = ((Number) invitation.get("companyId")).longValue();
		invitations.insertMembership(companyId, userId, invitation.get("role"));
		invitations.markAccepted(userId, invitation.get("id"));
		invitations.insertAcceptedAuditEvent(companyId, email, String.valueOf(invitation.get("id")),
				invitation.get("role"));
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
