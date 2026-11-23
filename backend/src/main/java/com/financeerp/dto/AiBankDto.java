package com.financeerp.dto;

import jakarta.validation.constraints.NotNull;

public final class AiBankDto {
	private AiBankDto() {
	}

	public record BankRequest(@NotNull Long bankAccountId) {
	}
}
