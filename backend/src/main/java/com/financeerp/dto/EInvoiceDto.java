package com.financeerp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class EInvoiceDto {
	private EInvoiceDto() {
	}

	public record IrnRequest(@NotNull @Pattern(regexp = "[0-9a-f]{64}") String irn) {
	}

	public record TransportRequest(@Min(1) @Max(4) int transMode, @Min(0) @Max(4000) int transDistance,
			@Pattern(regexp = "([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z])?") String transporterId,
			@Pattern(regexp = "([A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4})?") String vehicleNo,
			@Pattern(regexp = "[RO]") String vehicleType) {
	}
}
