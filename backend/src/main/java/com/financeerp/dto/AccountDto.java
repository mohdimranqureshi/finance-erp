package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class AccountDto {
	private AccountDto() {
	}

	public enum AccountType {
		ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
	}

	public record CreateAccountRequest(@NotBlank String code, @NotBlank String name, @NotNull AccountType accountType,
			Long parentAccountId) {
	}
}
