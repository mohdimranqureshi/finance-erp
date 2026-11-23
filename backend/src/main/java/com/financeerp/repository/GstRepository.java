package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Company GST profile, party and numbering lookups shared by invoices, bills and notes. */
@Repository
public class GstRepository {
	private final JdbcTemplate jdbc;

	public GstRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record GstProfileRow(String gstin, String stateCode) {
	}

	public Optional<GstProfileRow> findGstProfile(long companyId) {
		List<GstProfileRow> rows = jdbc.query("SELECT gstin,state_code FROM companies WHERE id=?",
				(rs, i) -> new GstProfileRow(rs.getString("gstin"), rs.getString("state_code")), companyId);
		return rows.stream().findFirst();
	}

	public int countActiveAccountOfType(long companyId, long accountId, String type) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true AND account_type=?",
				Integer.class, companyId, accountId, type);
		return n == null ? 0 : n;
	}

	public List<Map<String, Object>> findActiveParty(long companyId, long partyId, String type) {
		return jdbc.queryForList(
				"SELECT id,name,gstin,state_code AS \"stateCode\" FROM parties WHERE company_id=? AND id=? AND party_type=? AND active=true",
				companyId, partyId, type);
	}

	public int nextDocumentNumber(long companyId, String docType, String fiscalYear) {
		Integer n = jdbc.queryForObject(
				"INSERT INTO document_sequences(company_id,doc_type,fiscal_year,last_number) VALUES (?,?,?,1) ON CONFLICT (company_id,doc_type,fiscal_year) DO UPDATE SET last_number=document_sequences.last_number+1 RETURNING last_number",
				Integer.class, companyId, docType, fiscalYear);
		return n == null ? 1 : n;
	}
}