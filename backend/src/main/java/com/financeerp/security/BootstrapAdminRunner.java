package com.financeerp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time bootstrap: set all BOOTSTRAP_ADMIN_* variables, start once, then
 * remove them.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {
	private final JdbcTemplate jdbc;
	private final PasswordEncoder encoder;
	@Value("${app.bootstrap-admin.email:}")
	private String email;
	@Value("${app.bootstrap-admin.password:}")
	private String password;
	@Value("${app.bootstrap-admin.display-name:FinoraAI Administrator}")
	private String displayName;

	public BootstrapAdminRunner(JdbcTemplate jdbc, PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.encoder = encoder;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (email == null || email.isBlank() || password == null || password.isBlank())
			return;
		String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
			throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is not a valid email address.");
		if (password.length() < 16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
			throw new IllegalStateException(
					"BOOTSTRAP_ADMIN_PASSWORD must contain at least 16 characters and at most 72 UTF-8 bytes.");
		Integer existing = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)",
				Integer.class, normalized);
		if (existing != null && existing > 0) {
			// Never reset or overwrite an existing account's password during application
			// startup.
			return;
		}
		jdbc.update("INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?)", normalized,
				encoder.encode(password),
				displayName == null || displayName.isBlank() ? "FinoraAI Administrator" : displayName.trim());
		// Bootstrap only creates the identity. The first company owner must be assigned
		// through the normal audited company workflow.
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,'BOOTSTRAP_ADMIN_CREATED','USER',?,jsonb_build_object('email',?))",
				"SYSTEM_BOOTSTRAP", normalized, normalized);
	}
}
