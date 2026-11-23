package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BillCodingAssistDto {
	private BillCodingAssistDto() {
	}

	public record SuggestRequest(@NotBlank @Size(max = 300) String description, @Size(max = 180) String vendorName) {
	}
}
