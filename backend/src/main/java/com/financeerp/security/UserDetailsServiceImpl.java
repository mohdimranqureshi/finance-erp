package com.financeerp.security;

import com.financeerp.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {
	private final UserRepository users;
	private final LoginAttemptService attempts;

	public UserDetailsServiceImpl(UserRepository users, LoginAttemptService attempts) {
		this.users = users;
		this.attempts = attempts;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		UserRepository.Credentials row = users.findCredentialsByEmail(email);
		if (row == null)
			throw new UsernameNotFoundException("Invalid email or password.");
		List<SimpleGrantedAuthority> roles = row.admin()
				? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
				: List.of(new SimpleGrantedAuthority("ROLE_USER"));
		return new AppUser(row.id(), row.email(), row.passwordHash(), row.active(), !attempts.isLocked(email),
				row.mfaEnabled(), roles);
	}
}
