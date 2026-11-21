package com.financeerp.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;

/** Spring Security user that also carries the database id and whether TOTP MFA is enabled. */
public class AppUser extends User {
	private static final long serialVersionUID = 1L;
	private final long id;
	private final boolean mfaEnabled;

	public AppUser(long id, String email, String passwordHash, boolean enabled, boolean accountNonLocked,
			boolean mfaEnabled, Collection<? extends GrantedAuthority> authorities) {
		super(email, passwordHash, enabled, true, true, accountNonLocked, authorities);
		this.id = id;
		this.mfaEnabled = mfaEnabled;
	}

	public long getId() {
		return id;
	}

	public boolean isMfaEnabled() {
		return mfaEnabled;
	}
}
