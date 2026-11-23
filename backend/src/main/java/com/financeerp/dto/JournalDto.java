package com.financeerp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class JournalDto {
	private JournalDto() {
	}

	public record LineRequest(@NotNull Long accountId, String description, @NotNull @DecimalMin("0.0") BigDecimal debit,
			@NotNull @DecimalMin("0.0") BigDecimal credit) {
	}

	public record CreateJournalRequest(@NotNull LocalDate journalDate, @NotBlank String description,
			@NotEmpty List<@Valid LineRequest> lines, String idempotencyKey) {
	}

	public record ReverseJournalRequest(@NotNull LocalDate reversalDate, String description) {
	}
}
