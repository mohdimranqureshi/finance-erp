package com.financeerp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Revokes browser sessions on every request when the account was disabled or its session_version changed
 * (admin disable, MFA enable/disable/reset), or when the absolute session lifetime has passed. Costs one lookup on
 * the unique email index per session-authenticated request; Basic requests are checked by the
 * UserDetailsService instead. Not registered as a bean so it runs only inside the security filter chain.
 */
public class SessionValidationFilter extends OncePerRequestFilter {
	public static final String VERSION_ATTR = "financeerp.auth.sessionVersion";
	public static final String LOGIN_AT_ATTR = "financeerp.auth.loginAt";

	private final JdbcTemplate jdbc;
	private final Duration absoluteTimeout;

	public SessionValidationFilter(JdbcTemplate jdbc, Duration absoluteTimeout) {
		this.jdbc = jdbc;
		this.absoluteTimeout = absoluteTimeout;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
			Authentication sessionAuth = stored instanceof SecurityContext ctx ? ctx.getAuthentication() : null;
			if (sessionAuth != null && !isStillValid(session, sessionAuth.getName())) {
				session.invalidate();
				Authentication current = SecurityContextHolder.getContext().getAuthentication();
				if (current != null && sessionAuth.getName().equals(current.getName()))
					SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}

	private boolean isStillValid(HttpSession session, String email) {
		Object version = session.getAttribute(VERSION_ATTR);
		Object loginAt = session.getAttribute(LOGIN_AT_ATTR);
		if (!(version instanceof Integer expected) || !(loginAt instanceof Long started))
			return false;
		if (System.currentTimeMillis() - started > absoluteTimeout.toMillis())
			return false;
		List<Integer> rows = jdbc.query("SELECT session_version FROM app_users WHERE email=? AND active",
				(rs, i) -> rs.getInt(1), email);
		return !rows.isEmpty() && rows.get(0).intValue() == expected.intValue();
	}
}
