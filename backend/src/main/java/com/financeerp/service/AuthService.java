package com.financeerp.service;

import com.financeerp.dto.AuthDto;
import com.financeerp.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AuthService {
	private final UserRepository users;
	private final MfaService mfa;
	private final PasswordEncoder encoder;

	public AuthService(UserRepository users, MfaService mfa, PasswordEncoder encoder) {
		this.users = users;
		this.mfa = mfa;
		this.encoder = encoder;
	}

	/** Admin only (enforced in SecurityConfig). */
	public Map<String, Object> register(AuthDto.RegisterRequest request, String adminName) {
		String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
		if (email.length() > 254)
			throw new IllegalArgumentException("Email is too long.");
		if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes for BCrypt compatibility.");
		if (request.displayName().trim().isEmpty())
			throw new IllegalArgumentException("Display name cannot be blank.");
		try {
			Map<String, Object> created = users.insertUser(email, encoder.encode(request.password()),
					request.displayName().trim());
			users.insertUserCreatedAudit(adminName, String.valueOf(created.get("id")), email);
			return created;
		} catch (DuplicateKeyException ex) {
			throw new DuplicateKeyException("An account with this email already exists.", ex);
		}
	}

	public Map<String, Object> me(Authentication auth) {
		boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		boolean mfaEnabled = mfa.isEnabled(auth.getName());
		return Map.of("email", auth.getName(), "admin", admin, "mfaEnabled", mfaEnabled);
	}
}
