package com.financeerp;

import com.financeerp.security.Base32;
import com.financeerp.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Browser session login, CSRF, TOTP MFA, logout and account disabling, driven with manually managed cookies. */
class SessionAuthIntegrationTests extends IntegrationTestSupport {

	/** Minimal cookie-jar client: sends cookies back and X-XSRF-TOKEN on unsafe requests when asked to. */
	private final class Browser {
		final Map<String, String> cookies = new LinkedHashMap<>();
		final List<String> lastSetCookies = new java.util.ArrayList<>();

		@SuppressWarnings({ "unchecked", "rawtypes" })
		ResponseEntity<Map> call(HttpMethod method, String path, Object body, boolean sendCsrf) {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			if (!cookies.isEmpty())
				headers.add(HttpHeaders.COOKIE, String.join("; ",
						cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
			if (sendCsrf && cookies.containsKey("XSRF-TOKEN"))
				headers.add("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
			ResponseEntity<Map> response = rest.exchange(base() + path, method, new HttpEntity<>(body, headers),
					Map.class);
			lastSetCookies.clear();
			List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
			if (setCookies != null)
				for (String header : setCookies) {
					lastSetCookies.add(header);
					String pair = header.split(";", 2)[0];
					String name = pair.substring(0, pair.indexOf('=')).trim();
					String value = pair.substring(pair.indexOf('=') + 1).trim();
					if (value.isEmpty() || header.toLowerCase().contains("max-age=0"))
						cookies.remove(name);
					else
						cookies.put(name, value);
				}
			return response;
		}

		ResponseEntity<Map> get(String path) {
			return call(HttpMethod.GET, path, null, false);
		}

		ResponseEntity<Map> post(String path, Object body) {
			return call(HttpMethod.POST, path, body, true);
		}

		ResponseEntity<Map> postWithoutCsrf(String path, Object body) {
			return call(HttpMethod.POST, path, body, false);
		}

		ResponseEntity<Map> login(String email, String password, Map<String, String> extra) {
			if (!cookies.containsKey("XSRF-TOKEN"))
				assertThat(get("/api/v1/auth/csrf").getStatusCode()).isEqualTo(HttpStatus.OK);
			Map<String, String> body = new LinkedHashMap<>(extra);
			body.put("email", email);
			body.put("password", password);
			return post("/api/v1/auth/login", body);
		}
	}

	private record Account(String email, String password, long id) {
	}

	private Account account(String label) {
		String email = label + "-" + UUID.randomUUID() + "@example.test";
		String password = "a-strong-" + label + "-password";
		post(base() + "/api/v1/auth/register", Map.of("email", email, "password", password, "displayName", label), null,
				HttpStatus.OK);
		return new Account(email, password,
				jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email));
	}

	private int audits(String action, String email) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE company_id IS NULL AND action=? AND details->>'email'=?",
				Integer.class, action, email);
	}

	private static String code(String base32Secret, long step) {
		return Totp.generate(Base32.decode(base32Secret), step, 6);
	}

	private static long nowStep() {
		return Totp.timeStep(Instant.now().getEpochSecond());
	}

	@Test
	void sessionLoginNeedsCsrfRotatesSessionAndLogoutEndsIt() {
		Account user = account("session");
		Browser browser = new Browser();

		ResponseEntity<Map> csrf = browser.get("/api/v1/auth/csrf");
		assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.OK);
		String firstToken = browser.cookies.get("XSRF-TOKEN");
		assertThat(firstToken).isNotBlank().isEqualTo(csrf.getBody().get("token"));
		assertThat(csrf.getBody().get("headerName")).isEqualTo("X-XSRF-TOKEN");

		Map<String, String> credentials = Map.of("email", user.email(), "password", user.password());
		assertThat(browser.postWithoutCsrf("/api/v1/auth/login", credentials).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(browser.cookies).doesNotContainKey("JSESSIONID");

		ResponseEntity<Map> wrong = browser.login(user.email(), "not-the-password", Map.of());
		assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrong.getBody()).doesNotContainKey("mfaRequired");
		assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
		assertThat(audits("LOGIN_FAILED", user.email())).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action='LOGIN_FAILED' AND details::text LIKE '%not-the-password%'",
				Integer.class)).isZero();

		ResponseEntity<Map> ok = browser.login(user.email(), user.password(), Map.of());
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ok.getBody()).containsEntry("email", user.email()).containsEntry("admin", false)
				.containsEntry("mfaEnabled", false);
		String sessionCookie = browser.lastSetCookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst()
				.orElseThrow();
		assertThat(sessionCookie.toLowerCase()).contains("httponly").contains("samesite=strict");
		String firstSession = browser.cookies.get("JSESSIONID");
		assertThat(firstSession).isNotBlank();
		assertThat(browser.cookies.get("XSRF-TOKEN")).isNotEqualTo(firstToken);
		assertThat(audits("LOGIN_SUCCEEDED", user.email())).isEqualTo(1);

		ResponseEntity<Map> me = browser.get("/api/v1/auth/me");
		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(me.getBody()).containsEntry("email", user.email()).containsEntry("mfaEnabled", false);

		Map<String, String> company = Map.of("name", "Session Co", "baseCurrency", "INR");
		assertThat(browser.postWithoutCsrf("/api/v1/companies", company).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		HttpHeaders bad = new HttpHeaders();
		bad.setContentType(MediaType.APPLICATION_JSON);
		bad.add(HttpHeaders.COOKIE, "JSESSIONID=" + firstSession + "; XSRF-TOKEN=" + browser.cookies.get("XSRF-TOKEN"));
		bad.add("X-XSRF-TOKEN", "not-the-token");
		assertThat(rest.exchange(base() + "/api/v1/companies", HttpMethod.POST, new HttpEntity<>(company, bad),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(browser.post("/api/v1/companies", company).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Logging in again from a browser that already has a session issues a new session id (fixation protection).
		assertThat(browser.login(user.email(), user.password(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
		String secondSession = browser.cookies.get("JSESSIONID");
		assertThat(secondSession).isNotBlank().isNotEqualTo(firstSession);
		Browser stale = new Browser();
		stale.cookies.put("JSESSIONID", firstSession);
		assertThat(stale.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		assertThat(browser.postWithoutCsrf("/api/v1/auth/logout", Map.of()).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(browser.post("/api/v1/auth/logout", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
		assertThat(audits("LOGOUT", user.email())).isEqualTo(1);
		Browser afterLogout = new Browser();
		afterLogout.cookies.put("JSESSIONID", secondSession);
		assertThat(afterLogout.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@SuppressWarnings("unchecked")
	void totpMfaIsRequiredAtLoginAndCannotBeBypassedWithBasic() {
		Account user = account("mfa");
		Browser owner = new Browser();
		assertThat(owner.login(user.email(), user.password(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);

		Map<String, Object> setup = owner.post("/api/v1/auth/mfa/setup", Map.of()).getBody();
		String secret = (String) setup.get("secret");
		assertThat(secret).matches("[A-Z2-7]{32}");
		assertThat((String) setup.get("otpauthUri")).startsWith("otpauth://totp/").contains("secret=" + secret)
				.contains("issuer=");
		assertThat(owner.post("/api/v1/auth/mfa/enable", Map.of("code", "12345")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		long step = nowStep();
		String enableCode = code(secret, step);
		ResponseEntity<Map> enabled = owner.post("/api/v1/auth/mfa/enable", Map.of("code", enableCode));
		assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<String> recovery = (List<String>) enabled.getBody().get("recoveryCodes");
		assertThat(recovery).hasSize(10).doesNotHaveDuplicates();
		assertThat(audits("MFA_ENABLED", user.email())).isEqualTo(1);
		String stored = jdbc.queryForObject("SELECT mfa_secret_enc FROM app_users WHERE id=?", String.class, user.id());
		assertThat(stored).startsWith("v1:").doesNotContain(secret);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id=? AND code_hash=?",
				Integer.class, user.id(), recovery.get(0))).isZero();
		// The session that enabled MFA stays signed in.
		assertThat(owner.get("/api/v1/auth/me").getBody()).containsEntry("mfaEnabled", true);

		assertThat(rest.withBasicAuth(user.email(), user.password()).getForEntity(base() + "/api/v1/auth/me",
				String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		Browser second = new Browser();
		ResponseEntity<Map> challenge = second.login(user.email(), user.password(), Map.of());
		assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(challenge.getBody()).containsEntry("mfaRequired", true);
		assertThat(second.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		List<String> valid = List.of(code(secret, nowStep() - 1), code(secret, nowStep()), code(secret, nowStep() + 1));
		String wrongCode = java.util.stream.IntStream.range(0, 1000).mapToObj(i -> String.format("%06d", i))
				.filter(c -> !valid.contains(c)).findFirst().orElseThrow();
		ResponseEntity<Map> wrong = second.login(user.email(), user.password(), Map.of("totpCode", wrongCode));
		assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrong.getBody()).containsEntry("mfaRequired", true);
		assertThat(second.login(user.email(), user.password(), Map.of("totpCode", enableCode)).getStatusCode())
				.as("a code whose time step was already used is rejected").isEqualTo(HttpStatus.UNAUTHORIZED);
		ResponseEntity<Map> withTotp = second.login(user.email(), user.password(),
				Map.of("totpCode", code(secret, step + 1)));
		assertThat(withTotp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(withTotp.getBody()).containsEntry("mfaEnabled", true);
		assertThat(second.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.OK);

		Browser third = new Browser();
		assertThat(third.login(user.email(), user.password(), Map.of("recoveryCode", recovery.get(0).toLowerCase()))
				.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(new Browser().login(user.email(), user.password(), Map.of("recoveryCode", recovery.get(0)))
				.getStatusCode()).as("recovery codes are single use").isEqualTo(HttpStatus.UNAUTHORIZED);

		assertThat(owner.post("/api/v1/auth/mfa/disable", Map.of("password", "wrong-password", "code", recovery.get(1)))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		ResponseEntity<Map> disabled = owner.post("/api/v1/auth/mfa/disable",
				Map.of("password", user.password(), "code", recovery.get(1)));
		assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(audits("MFA_DISABLED", user.email())).isEqualTo(1);
		assertThat(owner.get("/api/v1/auth/me").getBody()).containsEntry("mfaEnabled", false);
		// Changing MFA revokes the user's other sessions.
		assertThat(third.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(rest.withBasicAuth(user.email(), user.password()).getForEntity(base() + "/api/v1/auth/me",
				String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void disabledUsersLoseSessionsAndCannotSignInWhileAdminsCanResetMfa() {
		Account user = account("disabled");
		Browser browser = new Browser();
		assertThat(browser.login(user.email(), user.password(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
		String users = base() + "/api/v1/admin/users/" + user.id();

		post(users + "/disable", Map.of(), user("Regular"), HttpStatus.FORBIDDEN);
		Map<String, Object> result = post(users + "/disable", Map.of(), adminClient(), HttpStatus.OK);
		assertThat(result).containsEntry("active", false);
		assertThat(audits("USER_DISABLED", user.email())).isEqualTo(1);

		assertThat(browser.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		ResponseEntity<Map> login = new Browser().login(user.email(), user.password(), Map.of());
		assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(login.getBody()).doesNotContainKey("mfaRequired");
		assertThat(rest.withBasicAuth(user.email(), user.password()).getForEntity(base() + "/api/v1/auth/me",
				String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		post(users + "/enable", Map.of(), adminClient(), HttpStatus.OK);
		assertThat(audits("USER_ENABLED", user.email())).isEqualTo(1);
		Browser again = new Browser();
		assertThat(again.login(user.email(), user.password(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);

		String secret = (String) again.post("/api/v1/auth/mfa/setup", Map.of()).getBody().get("secret");
		assertThat(again.post("/api/v1/auth/mfa/enable", Map.of("code", code(secret, nowStep()))).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		post(users + "/mfa/reset", Map.of(), adminClient(), HttpStatus.OK);
		assertThat(audits("MFA_RESET", user.email())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id=?", Integer.class,
				user.id())).isZero();
		assertThat(again.get("/api/v1/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		ResponseEntity<Map> afterReset = new Browser().login(user.email(), user.password(), Map.of());
		assertThat(afterReset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(afterReset.getBody()).containsEntry("mfaEnabled", false);
	}
}
