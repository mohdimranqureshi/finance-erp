package com.financeerp.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private final JdbcTemplate jdbc;
	private final PasswordEncoder encoder;

	public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.encoder = encoder;
	}

	public record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min = 12, max = 72) String password,
			@NotBlank @Size(max = 180) String displayName) {
	}

	/** Admin only (enforced in SecurityConfig). */
	@PostMapping("/register")
	public Map<String, Object> register(@Valid @RequestBody RegisterRequest request, Authentication admin) {
		String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
		if (email.length() > 254)
			throw new IllegalArgumentException("Email is too long.");
		if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
			throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes for BCrypt compatibility.");
		if (request.displayName().trim().isEmpty())
			throw new IllegalArgumentException("Display name cannot be blank.");
		try {
			Map<String, Object> created = jdbc.queryForMap(
					"INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?) RETURNING id,email,display_name AS \"displayName\",created_at AS \"createdAt\"",
					email, encoder.encode(request.password()), request.displayName().trim());
			jdbc.update(
					"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,'USER_CREATED','USER',?,jsonb_build_object('email',?))",
					admin.getName(), String.valueOf(created.get("id")), email);
			return created;
		} catch (DuplicateKeyException ex) {
			throw new DuplicateKeyException("An account with this email already exists.", ex);
		}
	}

	@GetMapping("/me")
	public Map<String, Object> me(Authentication auth) {
		boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		return Map.of("email", auth.getName(), "admin", admin);
	}
}
