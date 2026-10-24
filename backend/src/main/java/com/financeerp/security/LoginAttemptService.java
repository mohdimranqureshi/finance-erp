package com.financeerp.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Per-email brute-force throttle. State is in memory, so it is per instance and resets on restart. */
@Component
public class LoginAttemptService {
	static final int MAX_FAILURES = 5;
	static final Duration WINDOW = Duration.ofMinutes(15);
	static final Duration LOCK = Duration.ofMinutes(15);
	private static final int MAX_TRACKED = 10_000;

	private record State(int failures, Instant windowStart, Instant lockedUntil) {
	}

	private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

	public boolean isLocked(String email) {
		State s = states.get(key(email));
		return s != null && s.lockedUntil() != null && Instant.now().isBefore(s.lockedUntil());
	}

	@EventListener
	void onFailure(AuthenticationFailureBadCredentialsEvent event) {
		Instant now = Instant.now();
		if (states.size() >= MAX_TRACKED)
			states.values().removeIf(s -> expired(s, now));
		if (states.size() >= MAX_TRACKED)
			return;
		states.merge(key(event.getAuthentication().getName()), new State(1, now, null), (old, fresh) -> {
			if (old.lockedUntil() != null && now.isBefore(old.lockedUntil()))
				return old;
			boolean stale = old.lockedUntil() != null || now.isAfter(old.windowStart().plus(WINDOW));
			int failures = stale ? 1 : old.failures() + 1;
			Instant start = stale ? now : old.windowStart();
			return new State(failures, start, failures >= MAX_FAILURES ? now.plus(LOCK) : null);
		});
	}

	@EventListener
	void onSuccess(AuthenticationSuccessEvent event) {
		states.remove(key(event.getAuthentication().getName()));
	}

	private static boolean expired(State s, Instant now) {
		return s.lockedUntil() != null ? now.isAfter(s.lockedUntil()) : now.isAfter(s.windowStart().plus(WINDOW));
	}

	private static String key(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}
}
