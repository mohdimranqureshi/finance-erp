package com.financeerp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDto {
	private AuthDto() {
	}

	public record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min = 12, max = 72) String password,
			@NotBlank @Size(max = 180) String displayName) {
	}
}
