package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public final class AccountingPeriodDto {
	private AccountingPeriodDto() {
	}

	public record CreatePeriodRequest(@NotBlank String name, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
	}
}
