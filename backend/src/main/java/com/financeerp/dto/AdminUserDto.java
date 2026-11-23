package com.financeerp.dto;

import jakarta.validation.constraints.NotNull;

public final class AdminUserDto {
	private AdminUserDto() {
	}

	public record StatusRequest(@NotNull Boolean active) {
	}
}
