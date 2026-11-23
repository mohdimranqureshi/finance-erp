package com.financeerp.dto;

import com.financeerp.service.GstSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SalesInvoiceDto {
	private SalesInvoiceDto() {
	}

	public record LineRequest(@NotBlank @Size(max = 300) String description, @NotBlank String hsnSac,
			@NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice, @NotNull BigDecimal gstRate,
			Long revenueAccountId) {
	}

	/** tcsSection/tcsRate: income-tax TCS (section 206C) collected on top of the GST invoice value. */
	public record CreateRequest(@NotNull Long customerId, @NotNull LocalDate invoiceDate, LocalDate dueDate,
			String placeOfSupply, @NotEmpty @Size(max = GstSupport.MAX_LINES) List<@Valid LineRequest> lines,
			String tcsSection, BigDecimal tcsRate) {
	}
}
