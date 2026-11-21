package com.financeerp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/** TOTP enrolment, verification, recovery codes and resets. Secrets are AES-GCM encrypted at rest. */
@Service
public class MfaService {
	private static final Logger log = LoggerFactory.getLogger(MfaService.class);
	static final int RECOVERY_CODE_COUNT = 10;
	static final Duration PENDING_TTL = Duration.ofMinutes(15);
	private static final String CLEAR_MFA = "mfa_enabled=FALSE,mfa_secret_enc=NULL,mfa_pending_secret_enc=NULL,"
			+ "mfa_pending_created_at=NULL,mfa_enabled_at=NULL,mfa_last_used_step=NULL";

	private final JdbcTemplate jdbc;
	private final MfaSecretCipher cipher;
	private final PasswordEncoder encoder;
	private final LoginAttemptService attempts;
	private final SecurityAudit audit;
	private final String issuer;
	private final SecureRandom random = new SecureRandom();

	public MfaService(JdbcTemplate jdbc, MfaSecretCipher cipher, PasswordEncoder encoder, LoginAttemptService attempts,
			SecurityAudit audit, @Value("${app.security.mfa-issuer:FinoraAI}") String issuer) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.encoder = encoder;
		this.attempts = attempts;
		this.audit = audit;
		this.issuer = issuer;
	}

	public record Setup(String secret, String otpauthUri) {
	}

	private record Row(long id, String email, String passwordHash, boolean enabled, String secretEnc,
			String pendingEnc, Instant pendingCreatedAt, Long lastStep) {
	}

	public boolean isEnabled(String email) {
		List<Boolean> rows = jdbc.query("SELECT mfa_enabled FROM app_users WHERE lower(email)=lower(?)",
				(rs, i) -> rs.getBoolean(1), email);
		return !rows.isEmpty() && rows.get(0);
	}

	@Transactional
	public Setup beginSetup(String email) {
		Row user = lock(email);
		if (user.enabled())
			throw new IllegalArgumentException(
					"Two-factor authentication is already enabled. Disable it before setting it up again.");
		byte[] secret = new byte[20];
		random.nextBytes(secret);
		jdbc.update("UPDATE app_users SET mfa_pending_secret_enc=?,mfa_pending_created_at=now() WHERE id=?",
				cipher.encrypt(secret, aad(user.id())), user.id());
		String encoded = Base32.encode(secret);
		return new Setup(encoded, otpauthUri(user.email(), encoded));
	}

	/** Confirms the pending secret with a current code and returns the plaintext recovery codes exactly once. */
	@Transactional
	public List<String> enable(String email, String code) {
		Row user = lock(email);
		if (user.enabled())
			throw new IllegalArgumentException("Two-factor authentication is already enabled.");
		if (user.pendingEnc() == null || user.pendingCreatedAt() == null
				|| user.pendingCreatedAt().plus(PENDING_TTL).isBefore(Instant.now()))
			throw new IllegalArgumentException("Two-factor setup has expired or was not started. Start setup again.");
		OptionalLong step = Totp.verify(cipher.decrypt(user.pendingEnc(), aad(user.id())), code,
				Instant.now().getEpochSecond(), null);
		if (step.isEmpty())
			throw new IllegalArgumentException(
					"The verification code is not valid. Check that your device clock is correct and try again.");
		jdbc.update("UPDATE app_users SET mfa_enabled=TRUE,mfa_secret_enc=mfa_pending_secret_enc,"
				+ "mfa_pending_secret_enc=NULL,mfa_pending_created_at=NULL,mfa_enabled_at=now(),mfa_last_used_step=?,"
				+ "session_version=session_version+1 WHERE id=?", step.getAsLong(), user.id());
		List<String> codes = replaceRecoveryCodes(user.id());
		audit.record(user.email(), "MFA_ENABLED", user.id(), Map.of("email", user.email()));
		return codes;
	}

	/** Requires the current password and a valid TOTP or recovery code; failures count towards account lockout. */
	@Transactional(noRollbackFor = IllegalArgumentException.class)
	public void disable(String email, String password, String code) {
		Row user = lock(email);
		if (!user.enabled())
			throw new IllegalArgumentException("Two-factor authentication is not enabled.");
		String failure = "The password or verification code is incorrect, or the account is temporarily locked.";
		if (attempts.isLocked(user.email()))
			throw new IllegalArgumentException(failure);
		boolean passwordOk = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72
				&& encoder.matches(password, user.passwordHash());
		if (!passwordOk || !verifySecondFactor(user.id(), code)) {
			attempts.recordFailure(user.email());
			throw new IllegalArgumentException(failure);
		}
		clear(user.id());
		audit.record(user.email(), "MFA_DISABLED", user.id(), Map.of("email", user.email()));
	}

	/** Admin reset: removes MFA and revokes the user's sessions. The caller records the MFA_RESET audit event. */
	@Transactional
	public void reset(long userId) {
		clear(userId);
	}

	/** Verifies a TOTP code or, if it is not a 6-digit code, a recovery code. Codes are consumed on success. */
	public boolean verifySecondFactor(long userId, String code) {
		if (code == null)
			return false;
		String trimmed = code.replace(" ", "").trim();
		return trimmed.matches("\\d{" + Totp.DIGITS + "}") ? verifyTotp(userId, trimmed)
				: consumeRecoveryCode(userId, trimmed);
	}

	public boolean verifyTotp(long userId, String code) {
		Row user;
		try {
			user = load("WHERE id=?", userId, false);
		} catch (EmptyResultDataAccessException ex) {
			return false;
		}
		if (!user.enabled() || user.secretEnc() == null)
			return false;
		byte[] secret;
		try {
			secret = cipher.decrypt(user.secretEnc(), aad(userId));
		} catch (IllegalStateException ex) {
			log.error("TOTP secret for user {} cannot be decrypted; check MFA_ENCRYPTION_KEY. An admin can reset MFA.",
					userId);
			return false;
		}
		OptionalLong step = Totp.verify(secret, code, Instant.now().getEpochSecond(), user.lastStep());
		if (step.isEmpty())
			return false;
		// Conditional update makes acceptance atomic, so two concurrent requests cannot both use one step.
		return jdbc.update(
				"UPDATE app_users SET mfa_last_used_step=? WHERE id=? AND mfa_enabled AND (mfa_last_used_step IS NULL OR mfa_last_used_step<?)",
				step.getAsLong(), userId, step.getAsLong()) == 1;
	}

	public boolean consumeRecoveryCode(long userId, String code) {
		String normalized = normalizeRecoveryCode(code);
		if (normalized == null)
			return false;
		return jdbc.update(
				"UPDATE user_mfa_recovery_codes SET used_at=now() WHERE user_id=? AND code_hash=? AND used_at IS NULL "
						+ "AND EXISTS (SELECT 1 FROM app_users WHERE id=? AND mfa_enabled)",
				userId, hashRecoveryCode(userId, normalized), userId) == 1;
	}

	public int remainingRecoveryCodes(long userId) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id=? AND used_at IS NULL", Integer.class,
				userId);
		return n == null ? 0 : n;
	}

	private void clear(long userId) {
		jdbc.update("UPDATE app_users SET " + CLEAR_MFA + ",session_version=session_version+1 WHERE id=?", userId);
		jdbc.update("DELETE FROM user_mfa_recovery_codes WHERE user_id=?", userId);
	}

	private List<String> replaceRecoveryCodes(long userId) {
		jdbc.update("DELETE FROM user_mfa_recovery_codes WHERE user_id=?", userId);
		List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
		while (codes.size() < RECOVERY_CODE_COUNT) {
			byte[] bytes = new byte[10];
			random.nextBytes(bytes);
			String raw = Base32.encode(bytes);
			if (codes.stream().anyMatch(c -> c.replace("-", "").equals(raw)))
				continue;
			jdbc.update("INSERT INTO user_mfa_recovery_codes(user_id,code_hash) VALUES (?,?)", userId,
					hashRecoveryCode(userId, raw));
			codes.add(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-"
					+ raw.substring(12, 16));
		}
		return codes;
	}

	static String normalizeRecoveryCode(String code) {
		if (code == null)
			return null;
		String normalized = code.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z2-7]{16}") ? normalized : null;
	}

	static String hashRecoveryCode(long userId, String normalizedCode) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((userId + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String otpauthUri(String email, String secret) {
		String label = enc(issuer) + ":" + enc(email);
		return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
				+ "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String aad(long userId) {
		return "finance-erp:app_users:" + userId + ":totp";
	}

	private Row lock(String email) {
		try {
			return load("WHERE lower(email)=lower(?) AND active", email, true);
		} catch (EmptyResultDataAccessException ex) {
			throw new org.springframework.security.access.AccessDeniedException("Active user not found.");
		}
	}

	private Row load(String where, Object arg, boolean forUpdate) {
		return jdbc.queryForObject(
				"SELECT id,email,password_hash,mfa_enabled,mfa_secret_enc,mfa_pending_secret_enc,mfa_pending_created_at,"
						+ "mfa_last_used_step FROM app_users " + where + (forUpdate ? " FOR UPDATE" : ""),
				(rs, i) -> {
					Timestamp pending = rs.getTimestamp("mfa_pending_created_at");
					long last = rs.getLong("mfa_last_used_step");
					Long lastStep = rs.wasNull() ? null : last;
					return new Row(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
							rs.getBoolean("mfa_enabled"), rs.getString("mfa_secret_enc"),
							rs.getString("mfa_pending_secret_enc"), pending == null ? null : pending.toInstant(),
							lastStep);
				}, arg);
	}
}
