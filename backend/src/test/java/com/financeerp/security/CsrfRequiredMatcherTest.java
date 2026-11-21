package com.financeerp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class CsrfRequiredMatcherTest {
	private static MockHttpServletRequest request(String method, String uri, boolean sessionCookie, boolean basic) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		if (sessionCookie)
			request.setRequestedSessionId("abc");
		if (basic)
			request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		return request;
	}

	@Test
	void requiresCsrfForSessionRequestsAndLoginLogout() {
		RequestMatcher matcher = SecurityConfig.csrfRequired(true);
		assertThat(matcher.matches(request("POST", "/api/v1/companies", true, false))).isTrue();
		assertThat(matcher.matches(request("DELETE", "/api/v1/admin/users/1", true, false))).isTrue();
		assertThat(matcher.matches(request("POST", "/api/v1/auth/login", false, false))).isTrue();
		assertThat(matcher.matches(request("POST", "/api/v1/auth/login", false, true))).isTrue();
		assertThat(matcher.matches(request("POST", "/api/v1/auth/logout", false, false))).isTrue();
		assertThat(matcher.matches(request("GET", "/api/v1/companies", true, false))).isFalse();
	}

	@Test
	void exemptsBasicApiClientsOnlyWithoutASessionCookie() {
		RequestMatcher matcher = SecurityConfig.csrfRequired(true);
		assertThat(matcher.matches(request("POST", "/api/v1/companies", false, true))).isFalse();
		assertThat(matcher.matches(request("POST", "/api/v1/companies", true, true))).isTrue();
		assertThat(SecurityConfig.csrfRequired(false).matches(request("POST", "/api/v1/companies", true, true)))
				.isTrue();
	}

	@Test
	void anonymousRequestsWithoutAnyCredentialAreNotForgeable() {
		RequestMatcher matcher = SecurityConfig.csrfRequired(true);
		assertThat(matcher.matches(request("POST", "/api/v1/auth/accept-invitation", false, false))).isFalse();
	}
}
