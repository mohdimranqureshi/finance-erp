package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class FixedAssetRepository {
	private static final String SELECT = """
			SELECT a.id,a.asset_code AS "assetCode",a.name,a.category,a.cost,a.residual_value AS "residualValue",
			 a.put_to_use_date AS "putToUseDate",a.useful_life_years AS "usefulLifeYears",a.method,a.wdv_rate AS "wdvRate",
			 a.asset_account_id AS "assetAccountId",a.source_bill_id AS "sourceBillId",
			 a.capitalise_from_account_id AS "capitaliseFromAccountId",a.capitalisation_journal_id AS "capitalisationJournalId",
			 cj.status AS "capitalisationJournalStatus",
			 a.opening_accumulated_depreciation AS "openingAccumulatedDepreciation",a.opening_depreciated_to AS "openingDepreciatedTo",
			 a.accumulated_depreciation AS "accumulatedDepreciation",
			 CASE WHEN a.status='DISPOSED' THEN 0 ELSE a.cost-a.accumulated_depreciation END AS "netBookValue",
			 a.depreciated_to AS "depreciatedTo",
			 CASE WHEN a.status='DISPOSED' THEN 'DISPOSED'
			      WHEN a.accumulated_depreciation>=a.cost-a.residual_value THEN 'FULLY_DEPRECIATED' ELSE 'ACTIVE' END AS status,
			 a.disposal_date AS "disposalDate",a.disposal_proceeds AS "disposalProceeds",a.proceeds_account_id AS "proceedsAccountId",
			 a.disposal_journal_id AS "disposalJournalId",dj.status AS "disposalJournalStatus",a.disposal_gain_loss AS "disposalGainLoss",
			 a.created_at AS "createdAt"
			FROM fixed_assets a
			LEFT JOIN journal_entries cj ON cj.company_id=a.company_id AND cj.id=a.capitalisation_journal_id
			LEFT JOIN journal_entries dj ON dj.company_id=a.company_id AND dj.id=a.disposal_journal_id
			""";
	private static final String RUN_SELECT = """
			SELECT r.id,r.period_end AS "periodEnd",r.total_amount AS "totalAmount",r.asset_count AS "assetCount",
			 r.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",r.created_at AS "createdAt"
			FROM depreciation_runs r JOIN journal_entries j ON j.company_id=r.company_id AND j.id=r.journal_entry_id
			""";

	private final JdbcTemplate jdbc;

	public FixedAssetRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Integer countAssetsWithCode(long companyId, String assetCode) {
		return jdbc.queryForObject("SELECT count(*) FROM fixed_assets WHERE company_id=? AND lower(asset_code)=lower(?)",
				Integer.class, companyId, assetCode);
	}

	public Integer countPurchaseBills(long companyId, Long billId) {
		return jdbc.queryForObject("SELECT count(*) FROM purchase_bills WHERE company_id=? AND id=?", Integer.class,
				companyId, billId);
	}

	public Integer countActiveAccounts(long companyId, Long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true",
				Integer.class, companyId, accountId);
	}

	public Integer countBillLinesForExpenseAccount(long companyId, Long billId, Long accountId) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM purchase_bill_lines WHERE company_id=? AND bill_id=? AND expense_account_id=?",
				Integer.class, companyId, billId, accountId);
	}

	public Long insertAsset(long companyId, String code, String name, String category, BigDecimal cost,
			BigDecimal residual, LocalDate putToUseDate, Integer life, String method, BigDecimal wdvRate,
			long assetAccount, Long sourceBillId, BigDecimal opening, LocalDate openingTo, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO fixed_assets(company_id,asset_code,name,category,cost,residual_value,put_to_use_date,useful_life_years,method,wdv_rate,asset_account_id,source_bill_id,opening_accumulated_depreciation,opening_depreciated_to,accumulated_depreciation,depreciated_to,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, code, name, category, cost, residual, putToUseDate, life, method, wdvRate,
				assetAccount, sourceBillId, opening, openingTo, opening, openingTo, actor);
	}

	public void setCapitalisation(long companyId, Long assetId, Long fromAccountId, long journalId) {
		jdbc.update(
				"UPDATE fixed_assets SET capitalise_from_account_id=?,capitalisation_journal_id=? WHERE company_id=? AND id=?",
				fromAccountId, journalId, companyId, assetId);
	}

	public List<Map<String, Object>> findAssets(long companyId) {
		return jdbc.queryForList(SELECT + " WHERE a.company_id=? ORDER BY a.put_to_use_date,a.id", companyId);
	}

	public List<Map<String, Object>> findAsset(long companyId, long assetId) {
		return jdbc.queryForList(SELECT + " WHERE a.company_id=? AND a.id=?", companyId, assetId);
	}

	public List<Map<String, Object>> findAssetDepreciation(long companyId, long assetId) {
		return jdbc.queryForList("""
				SELECT d.id,d.source,d.run_id AS "runId",d.from_date AS "fromDate",d.to_date AS "toDate",d.amount,
				 d.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
				FROM fixed_asset_depreciation d
				JOIN journal_entries j ON j.company_id=d.company_id AND j.id=d.journal_entry_id
				WHERE d.company_id=? AND d.asset_id=? ORDER BY d.to_date,d.id
				""", companyId, assetId);
	}

	public List<Map<String, Object>> lockActiveAssets(long companyId) {
		return jdbc.queryForList(
				"SELECT * FROM fixed_assets WHERE company_id=? AND status='ACTIVE' ORDER BY id FOR UPDATE", companyId);
	}

	public java.sql.Date findLastRunPeriodEnd(long companyId) {
		return jdbc.queryForObject("SELECT max(period_end) FROM depreciation_runs WHERE company_id=?",
				java.sql.Date.class, companyId);
	}

	public Long insertRun(long companyId, LocalDate periodEnd, BigDecimal total, int assetCount, long journalId,
			long actor) {
		return jdbc.queryForObject(
				"INSERT INTO depreciation_runs(company_id,period_end,total_amount,asset_count,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, periodEnd, total, assetCount, journalId, actor);
	}

	public void insertRunDepreciation(long companyId, long assetId, Long runId, long journalId, LocalDate from,
			LocalDate to, BigDecimal amount) {
		jdbc.update(
				"INSERT INTO fixed_asset_depreciation(company_id,asset_id,source,run_id,journal_entry_id,from_date,to_date,amount) VALUES (?,?,'RUN',?,?,?,?,?)",
				companyId, assetId, runId, journalId, from, to, amount);
	}

	public void addAccumulatedDepreciation(long companyId, long assetId, BigDecimal amount, LocalDate depreciatedTo) {
		jdbc.update(
				"UPDATE fixed_assets SET accumulated_depreciation=accumulated_depreciation+?,depreciated_to=? WHERE company_id=? AND id=?",
				amount, depreciatedTo, companyId, assetId);
	}

	public List<Map<String, Object>> findRuns(long companyId) {
		return jdbc.queryForList(RUN_SELECT + " WHERE r.company_id=? ORDER BY r.period_end DESC", companyId);
	}

	public List<Map<String, Object>> findRun(long companyId, long runId) {
		return jdbc.queryForList(RUN_SELECT + " WHERE r.company_id=? AND r.id=?", companyId, runId);
	}

	public List<Map<String, Object>> findRunLines(long companyId, long runId) {
		return jdbc.queryForList("""
				SELECT d.asset_id AS "assetId",a.asset_code AS "assetCode",a.name,d.from_date AS "fromDate",d.to_date AS "toDate",d.amount
				FROM fixed_asset_depreciation d JOIN fixed_assets a ON a.company_id=d.company_id AND a.id=d.asset_id
				WHERE d.company_id=? AND d.run_id=? ORDER BY d.asset_id
				""", companyId, runId);
	}

	public List<Map<String, Object>> lockAsset(long companyId, long assetId) {
		return jdbc.queryForList("SELECT * FROM fixed_assets WHERE company_id=? AND id=? FOR UPDATE", companyId, assetId);
	}

	public void insertDisposalDepreciation(long companyId, long assetId, long journalId, LocalDate from, LocalDate to,
			BigDecimal amount) {
		jdbc.update(
				"INSERT INTO fixed_asset_depreciation(company_id,asset_id,source,journal_entry_id,from_date,to_date,amount) VALUES (?,?,'DISPOSAL',?,?,?,?)",
				companyId, assetId, journalId, from, to, amount);
	}

	public void markDisposed(long companyId, long assetId, BigDecimal accumulated, LocalDate depreciatedTo,
			LocalDate disposalDate, BigDecimal proceeds, Long proceedsAccountId, long journalId, BigDecimal gainLoss) {
		jdbc.update("""
				UPDATE fixed_assets SET status='DISPOSED',accumulated_depreciation=?,depreciated_to=?,disposal_date=?,
				 disposal_proceeds=?,proceeds_account_id=?,disposal_journal_id=?,disposal_gain_loss=?,disposed_at=now()
				WHERE company_id=? AND id=?
				""", accumulated, depreciatedTo, disposalDate, proceeds, proceedsAccountId, journalId, gainLoss, companyId,
				assetId);
	}
}
