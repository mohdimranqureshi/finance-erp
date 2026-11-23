package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MfaDto {
	private MfaDto() {
	}

	public record EnableRequest(@NotBlank @Size(max = 32) String code) {
	}

	public record DisableRequest(@NotBlank @Size(max = 200) String password, @NotBlank @Size(max = 32) String code) {
	}
}
