package com.financeerp.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
	private final JdbcTemplate jdbc;
	private final LoginAttemptService attempts;

	public UserDetailsServiceImpl(JdbcTemplate jdbc, LoginAttemptService attempts) {
		this.jdbc = jdbc;
		this.attempts = attempts;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return jdbc.query(
				"SELECT id,email,password_hash,active,is_admin,mfa_enabled FROM app_users WHERE lower(email)=lower(?)",
				rs -> {
					if (!rs.next())
						throw new UsernameNotFoundException("Invalid email or password.");
					List<SimpleGrantedAuthority> roles = rs.getBoolean("is_admin")
							? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
							: List.of(new SimpleGrantedAuthority("ROLE_USER"));
					return new AppUser(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
							rs.getBoolean("active"), !attempts.isLocked(email), rs.getBoolean("mfa_enabled"), roles);
				}, email);
	}
}
