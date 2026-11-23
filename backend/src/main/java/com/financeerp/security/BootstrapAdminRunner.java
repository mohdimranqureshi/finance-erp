package com.financeerp.security;

import com.financeerp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time bootstrap: set all BOOTSTRAP_ADMIN_* variables, start once, then
 * remove them.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {
	private final UserRepository users;
	private final PasswordEncoder encoder;
	@Value("${app.bootstrap-admin.email:${BOOTSTRAP_ADMIN_EMAIL:}}")
	private String email;
	@Value("${app.bootstrap-admin.password:${BOOTSTRAP_ADMIN_PASSWORD:}}")
	private String password;
	@Value("${app.bootstrap-admin.display-name:FinoraAI Administrator}")
	private String displayName;

	public BootstrapAdminRunner(UserRepository users, PasswordEncoder encoder) {
		this.users = users;
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
		Integer existing = users.countByEmail(normalized);
		if (existing != null && existing > 0) {
			// Never reset or overwrite an existing account's password during application
			// startup.
			return;
		}
		users.insertAdmin(normalized, encoder.encode(password),
				displayName == null || displayName.isBlank() ? "FinoraAI Administrator" : displayName.trim());
		// Bootstrap only creates the identity. The first company owner must be assigned
		// through the normal audited company workflow.
		users.insertBootstrapAdminAudit("SYSTEM_BOOTSTRAP", normalized, normalized);
	}
}
