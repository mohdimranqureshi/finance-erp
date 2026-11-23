package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;

@Repository
public class IndiaSetupRepository {
	private final JdbcTemplate jdbc;

	public IndiaSetupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> findProfile(long companyId) {
		return jdbc.queryForMap(
				"SELECT gstin,pan,tan,state_code AS \"stateCode\",address_line AS \"addressLine\",city,pincode FROM companies WHERE id=?", companyId);
	}

	public void updateProfile(long companyId, String gstin, String pan, String state, String addressLine, String city,
			String pincode) {
		jdbc.update("UPDATE companies SET gstin=?,pan=?,state_code=?,address_line=?,city=?,pincode=? WHERE id=?", gstin, pan,
				state, addressLine, city, pincode, companyId);
	}

	public void updateTan(long companyId, String tan) {
		jdbc.update("UPDATE companies SET tan=? WHERE id=?", tan, companyId);
	}

	/** Returns the number of rows created (0 when the account code already exists). */
	public int insertAccountIfAbsent(long companyId, String code, String name, String type, String systemKey) {
		return jdbc.update(
				"INSERT INTO accounts(company_id,code,name,account_type,system_key) VALUES (?,?,?,?,?) ON CONFLICT (company_id,code) DO NOTHING",
				companyId, code, name, type, systemKey);
	}

	public void adoptSystemKey(long companyId, String code, String type, String systemKey) {
		jdbc.update(
				"UPDATE accounts SET system_key=? WHERE company_id=? AND code=? AND account_type=? AND system_key IS NULL AND NOT EXISTS (SELECT 1 FROM accounts WHERE company_id=? AND system_key=?)",
				systemKey, companyId, code, type, companyId, systemKey);
	}

	public int countBySystemKey(long companyId, String systemKey) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND system_key=?",
				Integer.class, companyId, systemKey);
		return n == null ? 0 : n;
	}
}
