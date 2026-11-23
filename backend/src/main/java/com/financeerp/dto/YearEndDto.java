package com.financeerp.dto;

import jakarta.validation.constraints.NotNull;

public final class YearEndDto {
	private YearEndDto() {
	}

	public record CloseRequest(@NotNull Long periodId, @NotNull Long reservesAccountId) {
	}
}
