package com.financeerp.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private final JdbcTemplate jdbc;
	private final PasswordEncoder encoder;
	@Value("${app.registration.enabled:true}")
	private boolean registrationEnabled;

	public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.encoder = encoder;
	}

	public record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min = 12, max = 72) String password,
			@NotBlank @Size(max = 180) String displayName) {
	}

	@PostMapping("/register")
	public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) {
		if (!registrationEnabled)
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.FORBIDDEN,
					"Public registration is disabled. Use the approved administrator onboarding process.");
		String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
		if (email.length() > 254)
			throw new IllegalArgumentException("Email is too long.");
		if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes for BCrypt compatibility.");
		if (request.displayName().trim().isEmpty())
			throw new IllegalArgumentException("Display name cannot be blank.");
		try {
			return jdbc.queryForMap(
					"INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?) RETURNING id,email,display_name AS \"displayName\",created_at AS \"createdAt\"",
					email, encoder.encode(request.password()), request.displayName().trim());
		} catch (DuplicateKeyException ex) {
			throw new DuplicateKeyException("An account with this email already exists.", ex);
		}
	}
}
