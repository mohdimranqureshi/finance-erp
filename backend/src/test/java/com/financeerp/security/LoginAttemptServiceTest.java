package com.financeerp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

class LoginAttemptServiceTest {
	private static AuthenticationFailureBadCredentialsEvent failure(String email) {
		return new AuthenticationFailureBadCredentialsEvent(new UsernamePasswordAuthenticationToken(email, "x"),
				new BadCredentialsException("bad"));
	}

	@Test
	void locksAfterRepeatedFailuresCaseInsensitively() {
		LoginAttemptService svc = new LoginAttemptService();
		for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++)
			svc.onFailure(failure("User@Example.com"));
		assertFalse(svc.isLocked("user@example.com"));
		svc.onFailure(failure("user@example.com"));
		assertTrue(svc.isLocked("USER@example.com"));
		assertFalse(svc.isLocked("other@example.com"));
	}

	@Test
	void successClearsFailures() {
		LoginAttemptService svc = new LoginAttemptService();
		for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++)
			svc.onFailure(failure("a@b.in"));
		svc.onSuccess(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("a@b.in", "x")));
		svc.onFailure(failure("a@b.in"));
		assertFalse(svc.isLocked("a@b.in"));
	}
}
