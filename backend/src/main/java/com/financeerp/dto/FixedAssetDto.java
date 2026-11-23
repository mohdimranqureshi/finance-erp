package com.financeerp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class FixedAssetDto {
	private FixedAssetDto() {
	}

	public record CreateRequest(@NotBlank @Size(max = 200) String name, @NotBlank String category,
			@Size(max = 30) @Pattern(regexp = "[A-Za-z0-9/_.-]*") String assetCode, @NotNull BigDecimal cost,
			@NotNull LocalDate putToUseDate, @Min(1) @Max(100) Integer usefulLifeYears,
			@NotNull @Pattern(regexp = "SLM|WDV") String method, BigDecimal residualValue, Long assetAccountId,
			Long capitaliseFromAccountId, Long sourceBillId, BigDecimal openingAccumulatedDepreciation,
			LocalDate openingDepreciatedTo) {
	}

	public record RunRequest(@NotNull LocalDate periodEnd) {
	}

	public record DisposeRequest(@NotNull LocalDate disposalDate, @NotNull BigDecimal proceeds, Long proceedsAccountId) {
	}
}
