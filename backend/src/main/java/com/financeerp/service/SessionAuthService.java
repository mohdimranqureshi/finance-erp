package com.financeerp.service;

import com.financeerp.dto.SessionAuthDto;
import com.financeerp.repository.UserRepository;
import com.financeerp.security.AppUser;
import com.financeerp.security.LoginAttemptService;
import com.financeerp.security.SecurityAudit;
import com.financeerp.security.SecurityConfig;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Credential, lockout and second-factor checks for the browser session login, and its audit trail. */
@Service
public class SessionAuthService {
	private static final int UNAUTHORIZED = 401;
	private static final int OK = 200;

	/** Binds the authenticated user to the HTTP session; runs after every factor has been verified. */
	@FunctionalInterface
	public interface SessionEstablisher {
		void establish(AppUser user, Supplier<Integer> sessionVersion);
	}

	private final AuthenticationManager passwords;
	private final LoginAttemptService attempts;
	private final MfaService mfa;
	private final SecurityAudit audit;
	private final UserRepository users;

	public SessionAuthService(UserDetailsService userDetails, PasswordEncoder encoder, LoginAttemptService attempts,
			MfaService mfa, SecurityAudit audit, UserRepository users) {
		this.passwords = SecurityConfig.passwordAuthenticationManager(userDetails, encoder);
		this.attempts = attempts;
		this.mfa = mfa;
		this.audit = audit;
		this.users = users;
	}

	public SessionAuthDto.LoginResult login(SessionAuthDto.LoginRequest body, String clientIp,
			SessionEstablisher sessions) {
		String email = body == null || body.email() == null ? "" : body.email().trim().toLowerCase(Locale.ROOT);
		String password = body == null || body.password() == null ? "" : body.password();
		if (email.isEmpty() || email.length() > 254 || password.isEmpty()
				|| password.getBytes(StandardCharsets.UTF_8).length > 72) {
			if (!email.isEmpty())
				attempts.recordFailure(email);
			return failed(clientIp, email, "BAD_CREDENTIALS", false);
		}
		Authentication result;
		try {
			result = passwords.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
		} catch (BadCredentialsException ex) {
			attempts.recordFailure(email);
			return failed(clientIp, email, "BAD_CREDENTIALS", false);
		} catch (LockedException ex) {
			return failed(clientIp, email, "ACCOUNT_LOCKED", false);
		} catch (DisabledException ex) {
			return failed(clientIp, email, "ACCOUNT_DISABLED", false);
		} catch (AuthenticationException ex) {
			return failed(clientIp, email, "AUTHENTICATION_ERROR", false);
		}
		AppUser user = (AppUser) result.getPrincipal();
		String method = "PASSWORD";
		if (user.isMfaEnabled()) {
			String totp = blankToNull(body.totpCode());
			String recovery = blankToNull(body.recoveryCode());
			if (totp == null && recovery == null)
				return new SessionAuthDto.LoginResult(UNAUTHORIZED, Map.of("mfaRequired", true, "message",
						"Enter the 6-digit code from your authenticator app, or a recovery code."));
			boolean verified = totp != null ? mfa.verifyTotp(user.getId(), totp.replace(" ", ""))
					: mfa.consumeRecoveryCode(user.getId(), recovery);
			if (!verified) {
				attempts.recordFailure(email);
				return failed(clientIp, email, "MFA_INVALID", true);
			}
			method = totp != null ? "TOTP" : "RECOVERY_CODE";
		}
		attempts.recordSuccess(email);

		sessions.establish(user, () -> users.findSessionVersionById(user.getId()));

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("email", user.getUsername());
		details.put("method", method);
		details.put("ip", clientIp);
		if ("RECOVERY_CODE".equals(method))
			details.put("recoveryCodesRemaining", mfa.remainingRecoveryCodes(user.getId()));
		audit.record(user.getUsername(), "LOGIN_SUCCEEDED", user.getId(), details);
		boolean admin = user.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		return new SessionAuthDto.LoginResult(OK,
				Map.of("email", user.getUsername(), "admin", admin, "mfaEnabled", user.isMfaEnabled()));
	}

	/** Records LOGOUT for the currently authenticated caller, if any. */
	public void recordLogout(String clientIp) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("email", auth.getName());
			details.put("ip", clientIp);
			audit.record(auth.getName(), "LOGOUT", auth.getPrincipal() instanceof AppUser u ? u.getId() : null,
					details);
		}
	}

	private SessionAuthDto.LoginResult failed(String clientIp, String email, String reason, boolean mfaRequired) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("email", truncate(email, 254));
		details.put("reason", reason);
		details.put("ip", clientIp);
		audit.record("ANONYMOUS", "LOGIN_FAILED", null, details);
		Map<String, Object> responseBody = new LinkedHashMap<>();
		responseBody.put("message", mfaRequired
				? "Invalid email, password or verification code, or the account is temporarily locked."
				: SecurityConfig.GENERIC_LOGIN_FAILURE);
		if (mfaRequired)
			responseBody.put("mfaRequired", true);
		return new SessionAuthDto.LoginResult(UNAUTHORIZED, responseBody);
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
