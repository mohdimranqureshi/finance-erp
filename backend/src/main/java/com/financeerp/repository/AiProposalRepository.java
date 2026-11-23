package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Stored AI proposals plus the account, party and bill lookups the extraction pipeline validates against. */
@Repository
public class AiProposalRepository {
	private static final String SELECT = """
			SELECT id,kind,status,payload::text AS payload,warnings::text AS warnings,blockers::text AS blockers,
			 confidence,model,created_at AS "createdAt",decided_at AS "decidedAt",result_type AS "resultType",result_id AS "resultId"
			FROM ai_proposals
			""";
	private final JdbcTemplate jdbc;

	public AiProposalRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findActiveExpenseAccounts(long companyId) {
		return jdbc.queryForList(
				"SELECT id,code,name FROM accounts WHERE company_id=? AND account_type='EXPENSE' AND active=true ORDER BY code LIMIT 200",
				companyId);
	}

	public List<Map<String, Object>> findActiveIncomeAccounts(long companyId) {
		return jdbc.queryForList(
				"SELECT id,code,name FROM accounts WHERE company_id=? AND account_type='INCOME' AND active=true ORDER BY code LIMIT 200",
				companyId);
	}

	public List<Map<String, Object>> findActiveChartOfAccounts(long companyId) {
		return jdbc.queryForList(
				"SELECT id,code,name,account_type AS type FROM accounts WHERE company_id=? AND active=true ORDER BY code LIMIT 300",
				companyId);
	}

	public List<Map<String, Object>> findActivePartiesByGstin(long companyId, String partyType, String gstin) {
		return jdbc.queryForList(
				"SELECT id,gstin,state_code AS \"stateCode\" FROM parties WHERE company_id=? AND party_type=? AND active=true AND gstin=?",
				companyId, partyType, gstin);
	}

	public List<Map<String, Object>> findActivePartiesByName(long companyId, String partyType, String name) {
		return jdbc.queryForList(
				"SELECT id,gstin,state_code AS \"stateCode\" FROM parties WHERE company_id=? AND party_type=? AND active=true AND lower(name)=lower(?)",
				companyId, partyType, name);
	}

	public Integer countBillsByVendorInvoiceNumber(long companyId, long vendorId, String number) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM purchase_bills WHERE company_id=? AND vendor_id=? AND lower(vendor_invoice_number)=lower(?)",
				Integer.class, companyId, vendorId, number);
	}

	public List<Map<String, Object>> findProposals(long companyId, String status) {
		return jdbc.queryForList(SELECT
				+ " WHERE company_id=? AND (?::text IS NULL OR status=?) ORDER BY id DESC LIMIT 100", companyId, status, status);
	}

	public List<Map<String, Object>> findProposalForUpdate(long companyId, long proposalId) {
		return jdbc.queryForList(SELECT + " WHERE company_id=? AND id=? FOR UPDATE", companyId, proposalId);
	}

	public Map<String, Object> getProposal(long companyId, long id) {
		return jdbc.queryForMap(SELECT + " WHERE company_id=? AND id=?", companyId, id);
	}

	public long insertProposal(long companyId, String kind, String payload, String warnings, String blockers,
			BigDecimal confidence, String model, String inputSha256, int inputLength, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO ai_proposals(company_id,kind,payload,warnings,blockers,confidence,model,input_sha256,input_chars,created_by_user_id) VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?) RETURNING id",
				Long.class, companyId, kind, payload, warnings, blockers, confidence, model, inputSha256, inputLength, actor);
	}

	public void linkStatementLineToJournal(long companyId, long statementLineId, long journalId) {
		jdbc.update(
				"UPDATE bank_statement_lines SET journal_entry_id=? WHERE company_id=? AND id=? AND matched_payment_id IS NULL AND journal_entry_id IS NULL",
				journalId, companyId, statementLineId);
	}

	public void markAccepted(long companyId, long proposalId, long actor, String resultType, long resultId) {
		jdbc.update(
				"UPDATE ai_proposals SET status='ACCEPTED',decided_by_user_id=?,decided_at=now(),result_type=?,result_id=? WHERE company_id=? AND id=?",
				actor, resultType, resultId, companyId, proposalId);
	}

	public void markRejected(long companyId, long proposalId, long actor) {
		jdbc.update(
				"UPDATE ai_proposals SET status='REJECTED',decided_by_user_id=?,decided_at=now() WHERE company_id=? AND id=?",
				actor, companyId, proposalId);
	}
}
