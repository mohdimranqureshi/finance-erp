package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/parties")
public class PartyController {
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;

	public PartyController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
	}

	public record CreatePartyRequest(@Pattern(regexp = "CUSTOMER|VENDOR") String partyType,
			@NotBlank @Size(max = 180) String name, String gstin, String pan, @NotBlank String stateCode,
			@Size(max = 254) String email, @Size(max = 200) String addressLine, @Size(max = 100) String city,
			@Pattern(regexp = "([1-9][0-9]{5})?") String pincode) {
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreatePartyRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		String state = req.stateCode().trim();
		String gstin = req.gstin() == null || req.gstin().isBlank() ? null : req.gstin().trim().toUpperCase();
		String pan = req.pan() == null || req.pan().isBlank() ? null : req.pan().trim().toUpperCase();
		if (!IndiaTax.STATES.containsKey(state))
			throw new IllegalArgumentException("Unknown GST state code.");
		if (gstin != null) {
			if (!IndiaTax.isValidGstin(gstin))
				throw new IllegalArgumentException("GSTIN is invalid (format or checksum).");
			if (!gstin.startsWith(state))
				throw new IllegalArgumentException("GSTIN state code must match the selected state.");
			pan = gstin.substring(2, 12);
		}
		if (pan != null && !IndiaTax.isValidPan(pan))
			throw new IllegalArgumentException("PAN is invalid.");
		Map<String, Object> party = jdbc.queryForMap(
				"INSERT INTO parties(company_id,party_type,name,gstin,pan,state_code,email,address_line,city,pincode) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id,party_type AS \"partyType\",name,gstin,pan,state_code AS \"stateCode\",email,address_line AS \"addressLine\",city,pincode,active",
				companyId, req.partyType() == null ? "CUSTOMER" : req.partyType(), req.name().trim(), gstin, pan,
				state, req.email(), blank(req.addressLine()), blank(req.city()), blank(req.pincode()));
		poster.audit(companyId, "PARTY_CREATED", "PARTY", ((Number) party.get("id")).longValue(),
				String.valueOf(party.get("partyType")));
		return party;
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId,
			@RequestParam(required = false) String type) {
		access.requireMembership(companyId);
		if (type != null && !type.matches("CUSTOMER|VENDOR"))
			throw new IllegalArgumentException("type must be CUSTOMER or VENDOR.");
		return jdbc.queryForList(
				"SELECT id,party_type AS \"partyType\",name,gstin,pan,state_code AS \"stateCode\",email,active FROM parties WHERE company_id=? AND (?::text IS NULL OR party_type=?) ORDER BY name",
				companyId, type, type);
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
