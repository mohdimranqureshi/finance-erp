package com.financeerp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class CompanyMemberDto {
	private CompanyMemberDto() {
	}

	public record AddMemberRequest(@Email @NotBlank String email, @NotNull Role role) {
	}

	public enum Role {
		ACCOUNTANT, APPROVER, VIEWER
	}
}
