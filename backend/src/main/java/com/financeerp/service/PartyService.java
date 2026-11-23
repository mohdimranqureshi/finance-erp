package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.dto.PartyDto.CreatePartyRequest;
import com.financeerp.repository.PartyRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PartyService {
	private final PartyRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;

	public PartyService(PartyRepository repository, CompanyAccess access, LedgerPoster poster) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
	}

	public Map<String, Object> create(long companyId, CreatePartyRequest req) {
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
		Map<String, Object> party = repository.insertParty(companyId,
				req.partyType() == null ? "CUSTOMER" : req.partyType(), req.name().trim(), gstin, pan, state,
				req.email(), blank(req.addressLine()), blank(req.city()), blank(req.pincode()));
		poster.audit(companyId, "PARTY_CREATED", "PARTY", ((Number) party.get("id")).longValue(),
				String.valueOf(party.get("partyType")));
		return party;
	}

	public List<Map<String, Object>> list(long companyId, String type) {
		access.requireMembership(companyId);
		if (type != null && !type.matches("CUSTOMER|VENDOR"))
			throw new IllegalArgumentException("type must be CUSTOMER or VENDOR.");
		return repository.findParties(companyId, type);
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
