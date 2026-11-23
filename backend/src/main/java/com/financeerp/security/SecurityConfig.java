package com.financeerp.security;

import com.financeerp.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

@Configuration
@PropertySource("classpath:security-defaults.properties")
public class SecurityConfig {
	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
	static final String LOGIN_PATH = "/api/v1/auth/login";
	static final String LOGOUT_PATH = "/api/v1/auth/logout";
	public static final String GENERIC_LOGIN_FAILURE = "Invalid email or password, or the account is temporarily locked.";
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

	// No WWW-Authenticate header, so browsers show the app's own error instead of a native login dialog.
	private static final AuthenticationEntryPoint JSON_401 = (request, response, ex) -> {
		response.setStatus(401);
		response.setContentType("application/json");
		response.getWriter().write("{\"message\":\"" + GENERIC_LOGIN_FAILURE + "\"}");
	};

	private static final AccessDeniedHandler JSON_403 = (request, response, ex) -> {
		response.setStatus(403);
		response.setContentType("application/json");
		response.getWriter()
				.write(ex instanceof CsrfException
						? "{\"error\":\"CSRF\",\"message\":\"Missing or invalid CSRF token. Reload the page and try again.\"}"
						: "{\"error\":\"FORBIDDEN\",\"message\":\"Access denied.\"}");
	};

	/** Thrown for Basic authentication of an MFA-enabled account, which must sign in through the session login. */
	static final class BasicAuthNotAllowedException extends AuthenticationException {
		private static final long serialVersionUID = 1L;

		BasicAuthNotAllowedException() {
			super("HTTP Basic authentication is not allowed for accounts with two-factor authentication.");
		}
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService users, PasswordEncoder encoder,
			ApplicationEventPublisher events, UserRepository userRepository, CookieCsrfTokenRepository csrfTokenRepository,
			HttpSessionSecurityContextRepository sessionContextRepository,
			@Value("${app.security.basic-auth-enabled:true}") boolean basicAuthEnabled,
			@Value("${app.security.session-absolute-timeout:12h}") Duration absoluteTimeout) throws Exception {
		http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
				// Plain (non-XOR) handler: the SPA echoes the raw XSRF-TOKEN cookie value in X-XSRF-TOKEN.
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				.requireCsrfProtectionMatcher(csrfRequired(basicAuthEnabled)))
				.headers(headers -> headers.contentTypeOptions(Customizer.withDefaults())
						.frameOptions(frame -> frame.deny())
						.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
						.referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER))
						.permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=()"))
						.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; " + "script-src 'self'; "
								+ "style-src 'self' 'unsafe-inline'; " + "img-src 'self' data:; "
								+ "connect-src 'self'; " + "object-src 'none'; " + "base-uri 'self'; "
								+ "frame-ancestors 'none'")))
				.securityContext(sc -> sc.securityContextRepository(new DelegatingSecurityContextRepository(
						new RequestAttributeSecurityContextRepository(), sessionContextRepository)))
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				// An API never redirects after login, and saving requests would create sessions for anonymous calls.
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg", "/*.js",
								"/*.css", "/api/v1/auth/accept-invitation", LOGIN_PATH, LOGOUT_PATH,
								"/api/v1/auth/csrf", "/actuator/health", "/actuator/health/**", "/actuator/info",
								"/error")
						.permitAll().requestMatchers("/api/v1/auth/register", "/api/v1/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(JSON_401).accessDeniedHandler(JSON_403))
				.addFilterBefore(new SessionValidationFilter(userRepository, absoluteTimeout), AnonymousAuthenticationFilter.class)
				.formLogin(form -> form.disable()).logout(logout -> logout.disable());
		if (basicAuthEnabled)
			http.authenticationManager(basicAuthenticationManager(users, encoder, events))
					.httpBasic(basic -> basic.authenticationEntryPoint(JSON_401)
							.securityContextRepository(new RequestAttributeSecurityContextRepository()));
		else
			http.httpBasic(basic -> basic.disable());
		return http.build();
	}

	/**
	 * CSRF is required for unsafe requests that carry ambient browser credentials (a session cookie), and always for
	 * login and logout (login-CSRF / forced-logout). Requests with an Authorization: Basic header and no session
	 * cookie are exempt: browsers cannot attach that header cross-site without a CORS preflight, which is not
	 * allowed. Requests with neither are anonymous and carry no authority to forge; exempting them keeps
	 * unauthenticated calls answering 401 (or invitation acceptance working) rather than 403.
	 */
	static RequestMatcher csrfRequired(boolean basicAuthEnabled) {
		RequestMatcher unsafeMethod = request -> !SAFE_METHODS.contains(request.getMethod());
		RequestMatcher authEndpoint = request -> {
			String path = request.getRequestURI().substring(request.getContextPath().length());
			return LOGIN_PATH.equals(path) || LOGOUT_PATH.equals(path);
		};
		RequestMatcher sessionCookie = request -> request.getRequestedSessionId() != null;
		RequestMatcher basicApiClient = request -> basicAuthEnabled && isBasic(request)
				&& !sessionCookie.matches(request);
		return request -> unsafeMethod.matches(request) && (authEndpoint.matches(request)
				|| (sessionCookie.matches(request) && !basicApiClient.matches(request)));
	}

	private static boolean isBasic(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		return header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
	}

	/** Basic: same password check and lockout events as before, but MFA-enabled and disabled accounts are refused. */
	private static AuthenticationManager basicAuthenticationManager(UserDetailsService users, PasswordEncoder encoder,
			ApplicationEventPublisher events) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(users);
		provider.setPasswordEncoder(encoder);
		provider.setPostAuthenticationChecks(user -> {
			if (!user.isCredentialsNonExpired())
				throw new CredentialsExpiredException("User credentials have expired");
			if (user instanceof AppUser app && app.isMfaEnabled())
				throw new BasicAuthNotAllowedException();
		});
		ProviderManager manager = new ProviderManager(provider);
		manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(events));
		return manager;
	}

	/**
	 * Password verification for the session login. No event publisher: lockout bookkeeping is done explicitly by
	 * the login endpoint so that a correct password alone does not reset failed second-factor attempts.
	 */
	public static AuthenticationManager passwordAuthenticationManager(UserDetailsService users, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(users);
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(provider);
	}

	@Bean
	CookieCsrfTokenRepository csrfTokenRepository(
			@Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer(cookie -> {
			cookie.sameSite("Strict");
			if (secureCookies)
				cookie.secure(true);
		});
		return repository;
	}

	@Bean
	HttpSessionSecurityContextRepository httpSessionSecurityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/**
	 * Fails startup when no key is configured and ephemeral keys are not allowed (prod profile). An ephemeral key is
	 * lost on restart, which makes every enrolled TOTP secret undecryptable.
	 */
	@Bean
	MfaSecretCipher mfaSecretCipher(@Value("${app.security.mfa-encryption-key:}") String key,
			@Value("${app.security.mfa-allow-ephemeral-key:false}") boolean allowEphemeral) {
		if (key != null && !key.isBlank())
			return MfaSecretCipher.fromBase64(key);
		if (!allowEphemeral)
			throw new IllegalStateException("MFA_ENCRYPTION_KEY (app.security.mfa-encryption-key) is required: "
					+ "set it to a base64-encoded random 32-byte key, e.g. `openssl rand -base64 32`, "
					+ "stored in your secret manager.");
		log.warn("**************************************************************************************");
		log.warn("MFA_ENCRYPTION_KEY is not set: using an EPHEMERAL key. TOTP secrets enrolled now become");
		log.warn("unusable after a restart. Never run like this outside local development.");
		log.warn("**************************************************************************************");
		byte[] ephemeral = new byte[32];
		new SecureRandom().nextBytes(ephemeral);
		return new MfaSecretCipher(ephemeral);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}
}
