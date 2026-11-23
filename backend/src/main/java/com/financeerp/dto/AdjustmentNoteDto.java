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

public final class AdjustmentNoteDto {
	private AdjustmentNoteDto() {
	}

	public record LineRequest(@NotBlank @Size(max = 300) String description, @NotBlank String hsnSac,
			@NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice, @NotNull BigDecimal gstRate, Long accountId) {
	}

	public record NoteRequest(String vendorNoteNumber, @NotNull LocalDate noteDate, @NotBlank @Size(max = 300) String reason,
			@NotEmpty @Size(max = GstSupport.MAX_LINES) List<@Valid LineRequest> lines) {
	}
}
