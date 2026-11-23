package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PartyDto {
	private PartyDto() {
	}

	public record CreatePartyRequest(@Pattern(regexp = "CUSTOMER|VENDOR") String partyType,
			@NotBlank @Size(max = 180) String name, String gstin, String pan, @NotBlank String stateCode,
			@Size(max = 254) String email, @Size(max = 200) String addressLine, @Size(max = 100) String city,
			@Pattern(regexp = "([1-9][0-9]{5})?") String pincode) {
	}
}
