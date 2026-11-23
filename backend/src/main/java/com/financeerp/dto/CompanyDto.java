package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class CompanyDto {
	private CompanyDto() {
	}

	public record CreateCompanyRequest(@NotBlank String name, String legalName, String baseCurrency) {
	}

	public record SettingsRequest(@NotNull Boolean allowSelfApproval) {
	}
}
