package com.financeerp.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
	private final JdbcTemplate jdbc;

	public UserDetailsServiceImpl(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return jdbc.query("SELECT email,password_hash,active FROM app_users WHERE lower(email)=lower(?)", rs -> {
			if (!rs.next())
				throw new UsernameNotFoundException("Invalid email or password.");
			return User.withUsername(rs.getString("email")).password(rs.getString("password_hash"))
					.disabled(!rs.getBoolean("active")).roles("USER").build();
		}, email);
	}
}
