package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class Gstr2bDto {
	private Gstr2bDto() {
	}

	public record ResolveRequest(@NotBlank @Pattern(regexp = "ACCEPTED|FOLLOW_UP_VENDOR|IGNORED") String resolution,
			@Size(max = 500) String note) {
	}
}
