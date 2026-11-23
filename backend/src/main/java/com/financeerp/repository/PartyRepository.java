package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class PartyRepository {
	private final JdbcTemplate jdbc;

	public PartyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> insertParty(long companyId, String partyType, String name, String gstin, String pan,
			String state, String email, String addressLine, String city, String pincode) {
		return jdbc.queryForMap(
				"INSERT INTO parties(company_id,party_type,name,gstin,pan,state_code,email,address_line,city,pincode) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id,party_type AS \"partyType\",name,gstin,pan,state_code AS \"stateCode\",email,address_line AS \"addressLine\",city,pincode,active",
				companyId, partyType, name, gstin, pan, state, email, addressLine, city, pincode);
	}

	public List<Map<String, Object>> findParties(long companyId, String type) {
		return jdbc.queryForList(
				"SELECT id,party_type AS \"partyType\",name,gstin,pan,state_code AS \"stateCode\",email,active FROM parties WHERE company_id=? AND (?::text IS NULL OR party_type=?) ORDER BY name",
				companyId, type, type);
	}
}
