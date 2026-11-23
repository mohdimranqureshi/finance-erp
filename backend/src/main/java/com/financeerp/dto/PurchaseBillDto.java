package com.financeerp.dto;

import com.financeerp.service.GstSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseBillDto {
	private PurchaseBillDto() {
	}

	public record LineRequest(@NotBlank @Size(max = 300) String description, @NotBlank String hsnSac,
			@NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice, @NotNull BigDecimal gstRate,
			Long expenseAccountId) {
	}

	/** reverseCharge: the recipient pays the GST (section 9(3)/9(4)); the supplier's invoice carries no GST. */
	public record CreateRequest(@NotNull Long vendorId, @NotBlank @Size(max = 50) String vendorInvoiceNumber,
			@NotNull LocalDate billDate, LocalDate dueDate, Boolean itcEligible,
			@Pattern(regexp = "[0-9A-Z]{2,10}") String tdsSection, BigDecimal tdsRate,
			@NotEmpty @Size(max = GstSupport.MAX_LINES) List<@Valid LineRequest> lines, Boolean reverseCharge) {
	}
}
