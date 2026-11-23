package com.financeerp.dto;

import java.util.Map;

public final class SessionAuthDto {
	private SessionAuthDto() {
	}

	public record LoginRequest(String email, String password, String totpCode, String recoveryCode) {
	}

	/** Outcome of a login attempt: the HTTP status and JSON body the controller returns. */
	public record LoginResult(int status, Map<String, Object> body) {
	}
}
