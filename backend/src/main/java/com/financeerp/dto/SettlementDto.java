package com.financeerp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class SettlementDto {
	private SettlementDto() {
	}

	public record PaymentRequest(@NotNull LocalDate paymentDate, @NotNull BigDecimal amount, BigDecimal tdsAmount,
			Long bankAccountId, @Size(max = 100) String reference) {
	}
}
