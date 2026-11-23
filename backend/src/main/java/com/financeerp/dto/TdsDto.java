package com.financeerp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TdsDto {
	private TdsDto() {
	}

	public record Allocation(@NotNull Long billId, @NotNull BigDecimal amount) {
	}

	/** expenseAccountId receives interest and late fee; it is required when either is non-zero. */
	public record ChallanRequest(@NotNull @Pattern(regexp = "[0-9]{7}") String bsrCode,
			@NotNull @Pattern(regexp = "[0-9]{1,5}") String challanSerial, @NotNull LocalDate depositDate,
			@NotNull BigDecimal taxAmount, BigDecimal interestAmount, BigDecimal feeAmount, @NotNull Long bankAccountId,
			Long expenseAccountId, @Size(max = 500) List<@Valid Allocation> allocations) {
	}

	public record AllocateRequest(@NotNull @Size(min = 1, max = 500) List<@Valid Allocation> allocations) {
	}
}
