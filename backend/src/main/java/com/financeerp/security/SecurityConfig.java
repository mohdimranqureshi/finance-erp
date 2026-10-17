package com.financeerp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.disable()).headers(headers -> headers
				.contentTypeOptions(Customizer.withDefaults()).frameOptions(frame -> frame.deny())
				.httpStrictTransportSecurity(hsts -> hsts.disable())
				.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; " + "script-src 'self'; "
						+ "style-src 'self' 'unsafe-inline'; " + "img-src 'self' data:; " + "connect-src 'self'; "
						+ "object-src 'none'; " + "base-uri 'self'; " + "frame-ancestors 'none'")))
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
						"/*.js", "/*.css", "/api/v1/auth/register", "/api/v1/auth/accept-invitation",
						"/actuator/health", "/actuator/info", "/error").permitAll().anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults()).formLogin(form -> form.disable()).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}
}