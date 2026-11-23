package com.financeerp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class OpeningBalanceDto {
	private OpeningBalanceDto() {
	}

	public record LineRequest(Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
	}

	public record ReceivableRequest(Long customerId, String documentNumber, LocalDate documentDate, LocalDate dueDate,
			BigDecimal amount) {
	}

	public record PayableRequest(Long vendorId, String documentNumber, LocalDate documentDate, LocalDate dueDate,
			BigDecimal amount) {
	}

	public record CreateRequest(@NotNull LocalDate asOfDate, List<@Valid LineRequest> lines,
			List<@Valid ReceivableRequest> receivables, List<@Valid PayableRequest> payables) {
	}
}
