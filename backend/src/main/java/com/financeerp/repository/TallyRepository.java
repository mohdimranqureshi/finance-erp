package com.financeerp.repository;

import com.financeerp.domain.TallyImportPlanner.ExistingAccount;
import com.financeerp.domain.TallyImportPlanner.ExistingParty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes behind the Tally XML import and export. */
@Repository
public class TallyRepository {
	private final JdbcTemplate jdbc;

	public TallyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One posted journal line joined with its account, in export order. */
	public record ExportRow(long journalId, LocalDate date, String description, long accountId, String code,
			String name, String type, String systemKey, BigDecimal debit, BigDecimal credit) {
	}

	public void lockImports(long companyId) {
		jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, 0x7A11E1, (int) (companyId % Integer.MAX_VALUE));
	}

	public Long insertAccount(long companyId, String code, String name, String accountType) {
		return jdbc.queryForObject("INSERT INTO accounts(company_id,code,name,account_type) VALUES (?,?,?,?) RETURNING id",
				Long.class, companyId, code, name, accountType);
	}

	public Long insertParty(long companyId, String partyType, String name, String gstin, String pan,
			String stateCode) {
		return jdbc.queryForObject(
				"INSERT INTO parties(company_id,party_type,name,gstin,pan,state_code) VALUES (?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, partyType, name, gstin, pan, stateCode);
	}

	public List<ExistingAccount> findActiveAccounts(long companyId) {
		return jdbc.query("SELECT id,code,name,account_type FROM accounts WHERE company_id=? AND active=true",
				(rs, n) -> new ExistingAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				companyId);
	}

	public List<ExistingParty> findActiveParties(long companyId) {
		return jdbc.query("SELECT id,party_type,name,gstin FROM parties WHERE company_id=? AND active=true",
				(rs, n) -> new ExistingParty(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				companyId);
	}

	/** Idempotency key to journal id for journals created by earlier Tally imports. */
	public Map<String, Long> findTallyJournalKeys(long companyId) {
		Map<String, Long> keys = new HashMap<>();
		jdbc.query("SELECT idempotency_key,id FROM journal_entries WHERE company_id=? AND idempotency_key LIKE 'TALLY:%'",
				rs -> {
					keys.put(rs.getString(1), rs.getLong(2));
				}, companyId);
		return keys;
	}

	/** Null when the company has no state code (or does not exist). */
	public String findCompanyStateCode(long companyId) {
		List<String> state = jdbc.query("SELECT state_code FROM companies WHERE id=?", (rs, n) -> rs.getString(1),
				companyId);
		return state.isEmpty() ? null : state.get(0);
	}

	/** Null when there is no active account with this system key. */
	public Long findActiveSystemAccountId(long companyId, String systemKey) {
		List<Long> ids = jdbc.query("SELECT id FROM accounts WHERE company_id=? AND system_key=? AND active=true",
				(rs, n) -> rs.getLong(1), companyId, systemKey);
		return ids.isEmpty() ? null : ids.get(0);
	}

	public Integer countPostedJournals(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE company_id=? AND status='POSTED' AND journal_date BETWEEN ? AND ?",
				Integer.class, companyId, from, to);
	}

	public List<ExportRow> findPostedJournalLines(long companyId, LocalDate from, LocalDate to) {
		return jdbc.query(
				"SELECT je.id,je.journal_date,je.description,a.id,a.code,a.name,a.account_type,a.system_key,l.debit,l.credit "
						+ "FROM journal_lines l JOIN journal_entries je ON je.company_id=l.company_id AND je.id=l.journal_entry_id "
						+ "JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id "
						+ "WHERE l.company_id=? AND je.status='POSTED' AND je.journal_date BETWEEN ? AND ? "
						+ "ORDER BY je.journal_date,je.id,l.id",
				(rs, n) -> new ExportRow(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3),
						rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
						rs.getBigDecimal(9), rs.getBigDecimal(10)),
				companyId, from, to);
	}
}
