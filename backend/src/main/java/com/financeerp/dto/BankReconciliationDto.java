package com.financeerp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class BankReconciliationDto {
	private BankReconciliationDto() {
	}

	public record StatementLine(@NotNull LocalDate date, @Size(max = 300) String description, @NotNull BigDecimal amount,
			@Size(max = 100) String reference, @Size(max = 100) String externalId) {
	}

	public record ImportRequest(@NotNull Long bankAccountId, @NotEmpty @Size(max = 500) List<@Valid StatementLine> lines) {
	}

	public record MatchRequest(@NotNull Long paymentId) {
	}
}
