package com.financeerp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CompanyInvitationDto {
	private CompanyInvitationDto() {
	}

	public record CreateInvitationRequest(@Email @NotBlank @Size(max = 254) String email, @NotNull Role role) {
	}

	public enum Role {
		ACCOUNTANT, APPROVER, VIEWER
	}

	public record AcceptInvitationRequest(@NotBlank @Size(max = 100) String token,
			@NotBlank @Size(min = 12, max = 72) String password, @NotBlank @Size(max = 180) String displayName) {
	}
}
