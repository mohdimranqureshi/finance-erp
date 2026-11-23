package com.financeerp.service;

import com.financeerp.repository.MfaRepository;
import com.financeerp.repository.MfaRepository.MfaUser;
import com.financeerp.repository.UserRepository;
import com.financeerp.security.Base32;
import com.financeerp.security.LoginAttemptService;
import com.financeerp.security.MfaSecretCipher;
import com.financeerp.security.SecurityAudit;
import com.financeerp.security.Totp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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

	private final MfaRepository repository;
	private final UserRepository users;
	private final MfaSecretCipher cipher;
	private final PasswordEncoder encoder;
	private final LoginAttemptService attempts;
	private final SecurityAudit audit;
	private final String issuer;
	private final SecureRandom random = new SecureRandom();

	public MfaService(MfaRepository repository, UserRepository users, MfaSecretCipher cipher, PasswordEncoder encoder,
			LoginAttemptService attempts, SecurityAudit audit,
			@Value("${app.security.mfa-issuer:FinoraAI}") String issuer) {
		this.repository = repository;
		this.users = users;
		this.cipher = cipher;
		this.encoder = encoder;
		this.attempts = attempts;
		this.audit = audit;
		this.issuer = issuer;
	}

	public record Setup(String secret, String otpauthUri) {
	}

	public boolean isEnabled(String email) {
		return repository.isEnabled(email);
	}

	/** Current session_version of the account, used to keep the caller's own session valid after an MFA change. */
	public Integer sessionVersion(String email) {
		return users.findSessionVersionByEmail(email);
	}

	@Transactional
	public Setup beginSetup(String email) {
		MfaUser user = lock(email);
		if (user.enabled())
			throw new IllegalArgumentException(
					"Two-factor authentication is already enabled. Disable it before setting it up again.");
		byte[] secret = new byte[20];
		random.nextBytes(secret);
		repository.storePendingSecret(cipher.encrypt(secret, aad(user.id())), user.id());
		String encoded = Base32.encode(secret);
		return new Setup(encoded, otpauthUri(user.email(), encoded));
	}

	/** Confirms the pending secret with a current code and returns the plaintext recovery codes exactly once. */
	@Transactional
	public List<String> enable(String email, String code) {
		MfaUser user = lock(email);
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
		repository.activatePendingSecret(step.getAsLong(), user.id());
		List<String> codes = replaceRecoveryCodes(user.id());
		audit.record(user.email(), "MFA_ENABLED", user.id(), Map.of("email", user.email()));
		return codes;
	}

	/** Requires the current password and a valid TOTP or recovery code; failures count towards account lockout. */
	@Transactional(noRollbackFor = IllegalArgumentException.class)
	public void disable(String email, String password, String code) {
		MfaUser user = lock(email);
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
		MfaUser user = repository.findById(userId);
		if (user == null)
			return false;
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
		return repository.advanceLastUsedStep(userId, step.getAsLong()) == 1;
	}

	public boolean consumeRecoveryCode(long userId, String code) {
		String normalized = normalizeRecoveryCode(code);
		if (normalized == null)
			return false;
		return repository.markRecoveryCodeUsed(userId, hashRecoveryCode(userId, normalized)) == 1;
	}

	public int remainingRecoveryCodes(long userId) {
		Integer n = repository.countUnusedRecoveryCodes(userId);
		return n == null ? 0 : n;
	}

	private void clear(long userId) {
		repository.clearMfaColumns(userId);
		repository.deleteRecoveryCodes(userId);
	}

	private List<String> replaceRecoveryCodes(long userId) {
		repository.deleteRecoveryCodes(userId);
		List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
		while (codes.size() < RECOVERY_CODE_COUNT) {
			byte[] bytes = new byte[10];
			random.nextBytes(bytes);
			String raw = Base32.encode(bytes);
			if (codes.stream().anyMatch(c -> c.replace("-", "").equals(raw)))
				continue;
			repository.insertRecoveryCode(userId, hashRecoveryCode(userId, raw));
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

	private MfaUser lock(String email) {
		MfaUser user = repository.lockActiveByEmail(email);
		if (user == null)
			throw new org.springframework.security.access.AccessDeniedException("Active user not found.");
		return user;
	}
}
