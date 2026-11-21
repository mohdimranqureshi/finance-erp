package com.financeerp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Browser session login/logout and CSRF token bootstrap. */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionAuthController {
	private final AuthenticationManager passwords;
	private final LoginAttemptService attempts;
	private final MfaService mfa;
	private final SecurityAudit audit;
	private final JdbcTemplate jdbc;
	private final HttpSessionSecurityContextRepository contextRepository;
	private final CookieCsrfTokenRepository csrfTokens;
	private final String sessionCookieName;

	public SessionAuthController(UserDetailsService users, PasswordEncoder encoder, LoginAttemptService attempts,
			MfaService mfa, SecurityAudit audit, JdbcTemplate jdbc,
			HttpSessionSecurityContextRepository contextRepository, CookieCsrfTokenRepository csrfTokens,
			@Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
		this.passwords = SecurityConfig.passwordAuthenticationManager(users, encoder);
		this.attempts = attempts;
		this.mfa = mfa;
		this.audit = audit;
		this.jdbc = jdbc;
		this.contextRepository = contextRepository;
		this.csrfTokens = csrfTokens;
		this.sessionCookieName = sessionCookieName;
	}

	public record LoginRequest(String email, String password, String totpCode, String recoveryCode) {
	}

	/** Loading the deferred token makes CsrfFilter issue the XSRF-TOKEN cookie. */
	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken token) {
		return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		String email = body == null || body.email() == null ? "" : body.email().trim().toLowerCase(Locale.ROOT);
		String password = body == null || body.password() == null ? "" : body.password();
		if (email.isEmpty() || email.length() > 254 || password.isEmpty()
				|| password.getBytes(StandardCharsets.UTF_8).length > 72) {
			if (!email.isEmpty())
				attempts.recordFailure(email);
			return failed(request, email, "BAD_CREDENTIALS", false);
		}
		Authentication result;
		try {
			result = passwords.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
		} catch (BadCredentialsException ex) {
			attempts.recordFailure(email);
			return failed(request, email, "BAD_CREDENTIALS", false);
		} catch (LockedException ex) {
			return failed(request, email, "ACCOUNT_LOCKED", false);
		} catch (DisabledException ex) {
			return failed(request, email, "ACCOUNT_DISABLED", false);
		} catch (AuthenticationException ex) {
			return failed(request, email, "AUTHENTICATION_ERROR", false);
		}
		AppUser user = (AppUser) result.getPrincipal();
		String method = "PASSWORD";
		if (user.isMfaEnabled()) {
			String totp = blankToNull(body.totpCode());
			String recovery = blankToNull(body.recoveryCode());
			if (totp == null && recovery == null)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("mfaRequired", true, "message",
						"Enter the 6-digit code from your authenticator app, or a recovery code."));
			boolean verified = totp != null ? mfa.verifyTotp(user.getId(), totp.replace(" ", ""))
					: mfa.consumeRecoveryCode(user.getId(), recovery);
			if (!verified) {
				attempts.recordFailure(email);
				return failed(request, email, "MFA_INVALID", true);
			}
			method = totp != null ? "TOTP" : "RECOVERY_CODE";
		}
		attempts.recordSuccess(email);

		HttpSession existing = request.getSession(false);
		if (existing != null)
			request.changeSessionId();
		HttpSession session = request.getSession(true);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		Integer version = jdbc.queryForObject("SELECT session_version FROM app_users WHERE id=?", Integer.class,
				user.getId());
		session.setAttribute(SessionValidationFilter.VERSION_ATTR, version);
		session.setAttribute(SessionValidationFilter.LOGIN_AT_ATTR, System.currentTimeMillis());
		// A new CSRF token for the new session, so a token observed before login is useless afterwards.
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("email", user.getUsername());
		details.put("method", method);
		details.put("ip", request.getRemoteAddr());
		if ("RECOVERY_CODE".equals(method))
			details.put("recoveryCodesRemaining", mfa.remainingRecoveryCodes(user.getId()));
		audit.record(user.getUsername(), "LOGIN_SUCCEEDED", user.getId(), details);
		boolean admin = user.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		return ResponseEntity.ok(Map.of("email", user.getUsername(), "admin", admin, "mfaEnabled", user.isMfaEnabled()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("email", auth.getName());
			details.put("ip", request.getRemoteAddr());
			audit.record(auth.getName(), "LOGOUT", auth.getPrincipal() instanceof AppUser u ? u.getId() : null,
					details);
		}
		HttpSession session = request.getSession(false);
		if (session != null)
			session.invalidate();
		SecurityContextHolder.clearContext();
		String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
		response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(sessionCookieName, "").path(path).maxAge(0)
				.httpOnly(true).sameSite("Strict").secure(request.isSecure()).build().toString());
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
		return ResponseEntity.noContent().build();
	}

	private ResponseEntity<Map<String, Object>> failed(HttpServletRequest request, String email, String reason,
			boolean mfaRequired) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("email", SecurityAudit.truncate(email, 254));
		details.put("reason", reason);
		details.put("ip", request.getRemoteAddr());
		audit.record("ANONYMOUS", "LOGIN_FAILED", null, details);
		Map<String, Object> responseBody = new LinkedHashMap<>();
		responseBody.put("message", mfaRequired
				? "Invalid email, password or verification code, or the account is temporarily locked."
				: SecurityConfig.GENERIC_LOGIN_FAILURE);
		if (mfaRequired)
			responseBody.put("mfaRequired", true);
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
