package com.financeerp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class RevenueScheduleDto {
	private RevenueScheduleDto() {
	}

	public record ScheduleRequest(@NotNull LocalDate startDate, @Min(1) @Max(120) int months,
			@Size(max = 300) String description, BigDecimal amount, Long revenueAccountId) {
	}

	public record RecognizeRequest(@NotNull LocalDate asOf) {
	}
}
