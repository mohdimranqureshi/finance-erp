package com.financeerp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class SecurityConfig {
	// No WWW-Authenticate header, so browsers show the app's own error instead of a native login dialog.
	private static final AuthenticationEntryPoint JSON_401 = (request, response, ex) -> {
		response.setStatus(401);
		response.setContentType("application/json");
		response.getWriter().write("{\"message\":\"Invalid email or password, or the account is temporarily locked.\"}");
	};

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.disable()).headers(headers -> headers
				.contentTypeOptions(Customizer.withDefaults()).frameOptions(frame -> frame.deny())
				.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
				.referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER))
				.permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=()"))
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; " + "script-src 'self'; "
						+ "style-src 'self' 'unsafe-inline'; " + "img-src 'self' data:; " + "connect-src 'self'; "
						+ "object-src 'none'; " + "base-uri 'self'; " + "frame-ancestors 'none'")))
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
						"/*.js", "/*.css", "/api/v1/auth/accept-invitation",
						"/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll().requestMatchers("/api/v1/auth/register").hasRole("ADMIN").anyRequest().authenticated())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(JSON_401))
				.httpBasic(basic -> basic.authenticationEntryPoint(JSON_401)).formLogin(form -> form.disable()).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}
}