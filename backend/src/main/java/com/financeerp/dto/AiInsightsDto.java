package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AiInsightsDto {
	private AiInsightsDto() {
	}

	public record AskRequest(@NotBlank @Size(max = 500) String question) {
	}
}
