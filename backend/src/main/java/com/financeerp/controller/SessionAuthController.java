package com.financeerp.controller;

import com.financeerp.dto.SessionAuthDto;
import com.financeerp.security.AppUser;
import com.financeerp.security.SessionValidationFilter;
import com.financeerp.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Supplier;

/** Browser session login/logout and CSRF token bootstrap. */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionAuthController {
	private final SessionAuthService service;
	private final HttpSessionSecurityContextRepository contextRepository;
	private final CookieCsrfTokenRepository csrfTokens;
	private final String sessionCookieName;

	public SessionAuthController(SessionAuthService service, HttpSessionSecurityContextRepository contextRepository,
			CookieCsrfTokenRepository csrfTokens,
			@Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
		this.service = service;
		this.contextRepository = contextRepository;
		this.csrfTokens = csrfTokens;
		this.sessionCookieName = sessionCookieName;
	}

	/** Loading the deferred token makes CsrfFilter issue the XSRF-TOKEN cookie. */
	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken token) {
		return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) SessionAuthDto.LoginRequest body,
			HttpServletRequest request, HttpServletResponse response) {
		SessionAuthDto.LoginResult result = service.login(body, request.getRemoteAddr(),
				(user, version) -> establishSession(user, version, request, response));
		return ResponseEntity.status(result.status()).body(result.body());
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		service.recordLogout(request.getRemoteAddr());
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

	private void establishSession(AppUser user, Supplier<Integer> sessionVersion, HttpServletRequest request,
			HttpServletResponse response) {
		HttpSession existing = request.getSession(false);
		if (existing != null)
			request.changeSessionId();
		HttpSession session = request.getSession(true);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		Integer version = sessionVersion.get();
		session.setAttribute(SessionValidationFilter.VERSION_ATTR, version);
		session.setAttribute(SessionValidationFilter.LOGIN_AT_ATTR, System.currentTimeMillis());
		// A new CSRF token for the new session, so a token observed before login is useless afterwards.
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
	}
}
