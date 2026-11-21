package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Fixed asset register with Schedule II style book depreciation. Capitalisation, depreciation runs and disposals create
 * DRAFT journals only; they reach the ledger through the maker-checker workflow.
 */
@RestController
public class FixedAssetController {
	private static final Set<String> WRITERS = Set.of("OWNER", "ACCOUNTANT");
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public FixedAssetController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record CreateRequest(@NotBlank @Size(max = 200) String name, @NotBlank String category,
			@Size(max = 30) @Pattern(regexp = "[A-Za-z0-9/_.-]*") String assetCode, @NotNull BigDecimal cost,
			@NotNull LocalDate putToUseDate, @Min(1) @Max(100) Integer usefulLifeYears,
			@NotNull @Pattern(regexp = "SLM|WDV") String method, BigDecimal residualValue, Long assetAccountId,
			Long capitaliseFromAccountId, Long sourceBillId, BigDecimal openingAccumulatedDepreciation,
			LocalDate openingDepreciatedTo) {
	}

	public record RunRequest(@NotNull LocalDate periodEnd) {
	}

	public record DisposeRequest(@NotNull LocalDate disposalDate, @NotNull BigDecimal proceeds, Long proceedsAccountId) {
	}

	@GetMapping("/api/v1/fixed-assets/categories")
	public Map<String, Object> categories() {
		List<Map<String, Object>> categories = new ArrayList<>();
		Depreciation.CATEGORIES.forEach(c -> categories.add(Depreciation.describe(c)));
		return Map.of("categories", categories, "methods", List.of("SLM", "WDV"), "defaultResidualPercent", 5,
				"referenceData", true, "note", Depreciation.CATEGORY_NOTE);
	}

	@PostMapping("/api/v1/companies/{companyId}/fixed-assets")
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		long actor = access.requireRole(companyId, WRITERS);
		Depreciation.Category category = Depreciation.category(req.category().trim())
				.orElseThrow(() -> new IllegalArgumentException("Unknown asset category " + req.category() + "."));
		Integer life = req.usefulLifeYears() != null ? req.usefulLifeYears() : category.usefulLifeYears();
		if (life == null)
			throw new IllegalArgumentException("usefulLifeYears is required for category " + category.code() + ".");
		BigDecimal cost = money(req.cost(), "Cost");
		if (cost.signum() <= 0)
			throw new IllegalArgumentException("Cost must be greater than zero.");
		BigDecimal residual = req.residualValue() == null ? Depreciation.defaultResidual(cost)
				: money(req.residualValue(), "Residual value");
		if (residual.compareTo(cost) > 0)
			throw new IllegalArgumentException("Residual value cannot exceed the cost.");
		Depreciation.Method method = Depreciation.Method.valueOf(req.method());
		BigDecimal rate = method == Depreciation.Method.WDV ? Depreciation.wdvRate(cost, residual, life) : null;

		BigDecimal opening = req.openingAccumulatedDepreciation() == null ? BigDecimal.ZERO.setScale(2)
				: money(req.openingAccumulatedDepreciation(), "Opening accumulated depreciation");
		LocalDate openingTo = req.openingDepreciatedTo();
		if (opening.signum() > 0 && openingTo == null)
			throw new IllegalArgumentException("openingDepreciatedTo is required with opening accumulated depreciation.");
		if (openingTo != null && openingTo.isBefore(req.putToUseDate().minusDays(1)))
			throw new IllegalArgumentException("openingDepreciatedTo cannot be before the put-to-use date.");
		if (opening.compareTo(cost.subtract(residual)) > 0)
			throw new IllegalArgumentException("Opening accumulated depreciation cannot exceed cost less residual value.");

		long assetAccount;
		if (req.assetAccountId() != null) {
			gst.requireAccount(companyId, req.assetAccountId(), "ASSET");
			assetAccount = req.assetAccountId();
		} else {
			assetAccount = poster.systemAccount(companyId, "FIXED_ASSETS");
		}
		if (assetAccount == poster.systemAccount(companyId, "ACCUMULATED_DEPRECIATION"))
			throw new IllegalArgumentException("The accumulated depreciation account cannot be used as the asset account.");

		if (req.assetCode() != null && !req.assetCode().isBlank()) {
			Integer dup = jdbc.queryForObject(
					"SELECT count(*) FROM fixed_assets WHERE company_id=? AND lower(asset_code)=lower(?)", Integer.class,
					companyId, req.assetCode().trim());
			if (dup != null && dup > 0)
				throw new IllegalArgumentException("Asset code " + req.assetCode().trim() + " is already in use.");
		}
		if (req.sourceBillId() != null) {
			Integer bill = jdbc.queryForObject("SELECT count(*) FROM purchase_bills WHERE company_id=? AND id=?",
					Integer.class, companyId, req.sourceBillId());
			if (bill == null || bill == 0)
				throw new IllegalArgumentException("Purchase bill not found.");
		}
		if (req.capitaliseFromAccountId() != null) {
			if (opening.signum() > 0 || openingTo != null)
				throw new IllegalArgumentException(
						"Brought-forward assets are recorded through opening balances; do not combine them with capitaliseFromAccountId.");
			if (req.capitaliseFromAccountId() == assetAccount)
				throw new IllegalArgumentException("capitaliseFromAccountId must differ from the asset account.");
			Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=? AND active=true",
					Integer.class, companyId, req.capitaliseFromAccountId());
			if (n == null || n == 0)
				throw new IllegalArgumentException("Account " + req.capitaliseFromAccountId() + " must be an active account of this company.");
			if (req.sourceBillId() != null) {
				Integer used = jdbc.queryForObject(
						"SELECT count(*) FROM purchase_bill_lines WHERE company_id=? AND bill_id=? AND expense_account_id=?",
						Integer.class, companyId, req.sourceBillId(), req.capitaliseFromAccountId());
				if (used == null || used == 0)
					throw new IllegalArgumentException("The source bill has no line booked to capitaliseFromAccountId.");
			}
			poster.requireOpenPeriod(companyId, req.putToUseDate());
		}

		String code = req.assetCode() == null || req.assetCode().isBlank() ? null : req.assetCode().trim();
		Long id = jdbc.queryForObject(
				"INSERT INTO fixed_assets(company_id,asset_code,name,category,cost,residual_value,put_to_use_date,useful_life_years,method,wdv_rate,asset_account_id,source_bill_id,opening_accumulated_depreciation,opening_depreciated_to,accumulated_depreciation,depreciated_to,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, code, req.name().trim(), category.code(), cost, residual, req.putToUseDate(), life,
				method.name(), rate, assetAccount, req.sourceBillId(), opening, openingTo, opening, openingTo, actor);
		if (req.capitaliseFromAccountId() != null) {
			String label = "Capitalise fixed asset " + (code == null ? "#" + id : code) + ": " + req.name().trim();
			long journalId = poster.createDraftJournal(companyId, req.putToUseDate(), label, "FA_CAPITALISE:" + id, actor,
					List.of(new LedgerPoster.Line(assetAccount, label, cost, BigDecimal.ZERO),
							new LedgerPoster.Line(req.capitaliseFromAccountId(), label, BigDecimal.ZERO, cost)));
			jdbc.update(
					"UPDATE fixed_assets SET capitalise_from_account_id=?,capitalisation_journal_id=? WHERE company_id=? AND id=?",
					req.capitaliseFromAccountId(), journalId, companyId, id);
		}
		poster.audit(companyId, "FIXED_ASSET_CREATED", "FIXED_ASSET", id,
				method + " cost " + cost.toPlainString() + ", life " + life + " years");
		return get(companyId, id);
	}

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

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets")
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(SELECT + " WHERE a.company_id=? ORDER BY a.put_to_use_date,a.id", companyId);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/{assetId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long assetId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE a.company_id=? AND a.id=?", companyId, assetId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Fixed asset not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("depreciation", jdbc.queryForList("""
				SELECT d.id,d.source,d.run_id AS "runId",d.from_date AS "fromDate",d.to_date AS "toDate",d.amount,
				 d.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
				FROM fixed_asset_depreciation d
				JOIN journal_entries j ON j.company_id=d.company_id AND j.id=d.journal_entry_id
				WHERE d.company_id=? AND d.asset_id=? ORDER BY d.to_date,d.id
				""", companyId, assetId));
		return out;
	}

	@PostMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs")
	@Transactional
	public Map<String, Object> run(@PathVariable long companyId, @Valid @RequestBody RunRequest req) {
		long actor = access.requireRole(companyId, WRITERS);
		LocalDate periodEnd = req.periodEnd();
		poster.requireOpenPeriod(companyId, periodEnd);
		// Locking the active assets serialises runs and disposals for this company.
		List<Map<String, Object>> assets = jdbc.queryForList(
				"SELECT * FROM fixed_assets WHERE company_id=? AND status='ACTIVE' ORDER BY id FOR UPDATE", companyId);
		java.sql.Date last = jdbc.queryForObject("SELECT max(period_end) FROM depreciation_runs WHERE company_id=?",
				java.sql.Date.class, companyId);
		LocalDate previous = last == null ? null : last.toLocalDate();
		if (previous != null && !periodEnd.isAfter(previous))
			throw new IllegalArgumentException("periodEnd must be after the previous depreciation run (" + previous + ").");
		record Charge(long assetId, LocalDate from, BigDecimal amount) {
		}
		List<Charge> charges = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> a : assets) {
			LocalDate from = nextDay(a);
			if (from.isAfter(periodEnd))
				continue;
			BigDecimal amount = Depreciation.chargeTo(toCalc(a), (BigDecimal) a.get("accumulated_depreciation"), periodEnd);
			if (amount.signum() <= 0)
				continue;
			charges.add(new Charge(((Number) a.get("id")).longValue(), from, amount));
			total = total.add(amount);
		}
		if (charges.isEmpty())
			throw new IllegalArgumentException("Nothing to depreciate up to " + periodEnd + ".");
		String label = "Depreciation up to " + periodEnd;
		long journalId = poster.createDraftJournal(companyId, periodEnd, label, "FA_DEPRECIATION:" + periodEnd, actor,
				List.of(new LedgerPoster.Line(poster.systemAccount(companyId, "DEPRECIATION"), label, total, BigDecimal.ZERO),
						new LedgerPoster.Line(poster.systemAccount(companyId, "ACCUMULATED_DEPRECIATION"), label,
								BigDecimal.ZERO, total)));
		Long runId = jdbc.queryForObject(
				"INSERT INTO depreciation_runs(company_id,period_end,total_amount,asset_count,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, periodEnd, total, charges.size(), journalId, actor);
		for (Charge c : charges) {
			jdbc.update(
					"INSERT INTO fixed_asset_depreciation(company_id,asset_id,source,run_id,journal_entry_id,from_date,to_date,amount) VALUES (?,?,'RUN',?,?,?,?,?)",
					companyId, c.assetId(), runId, journalId, c.from(), periodEnd, c.amount());
			jdbc.update(
					"UPDATE fixed_assets SET accumulated_depreciation=accumulated_depreciation+?,depreciated_to=? WHERE company_id=? AND id=?",
					c.amount(), periodEnd, companyId, c.assetId());
		}
		poster.audit(companyId, "DEPRECIATION_RUN_CREATED", "DEPRECIATION_RUN", runId,
				"Up to " + periodEnd + ": " + total.toPlainString() + " across " + charges.size() + " assets");
		return getRun(companyId, runId);
	}

	private static final String RUN_SELECT = """
			SELECT r.id,r.period_end AS "periodEnd",r.total_amount AS "totalAmount",r.asset_count AS "assetCount",
			 r.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",r.created_at AS "createdAt"
			FROM depreciation_runs r JOIN journal_entries j ON j.company_id=r.company_id AND j.id=r.journal_entry_id
			""";

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs")
	public List<Map<String, Object>> runs(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(RUN_SELECT + " WHERE r.company_id=? ORDER BY r.period_end DESC", companyId);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs/{runId}")
	public Map<String, Object> getRun(@PathVariable long companyId, @PathVariable long runId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(RUN_SELECT + " WHERE r.company_id=? AND r.id=?", companyId, runId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Depreciation run not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", jdbc.queryForList("""
				SELECT d.asset_id AS "assetId",a.asset_code AS "assetCode",a.name,d.from_date AS "fromDate",d.to_date AS "toDate",d.amount
				FROM fixed_asset_depreciation d JOIN fixed_assets a ON a.company_id=d.company_id AND a.id=d.asset_id
				WHERE d.company_id=? AND d.run_id=? ORDER BY d.asset_id
				""", companyId, runId));
		return out;
	}

	/** GST on the sale of a used asset is out of scope: raise a sales invoice separately if output tax is due. */
	@PostMapping("/api/v1/companies/{companyId}/fixed-assets/{assetId}/dispose")
	@Transactional
	public Map<String, Object> dispose(@PathVariable long companyId, @PathVariable long assetId,
			@Valid @RequestBody DisposeRequest req) {
		long actor = access.requireRole(companyId, WRITERS);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT * FROM fixed_assets WHERE company_id=? AND id=? FOR UPDATE", companyId, assetId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Fixed asset not found.");
		Map<String, Object> a = rows.get(0);
		if (!"ACTIVE".equals(a.get("status")))
			throw new IllegalArgumentException("Only active assets can be disposed of.");
		LocalDate disposal = req.disposalDate();
		if (disposal.isBefore(date(a, "put_to_use_date")))
			throw new IllegalArgumentException("Disposal date cannot be before the put-to-use date.");
		LocalDate depreciatedTo = date(a, "depreciated_to");
		if (depreciatedTo != null && !depreciatedTo.isBefore(disposal))
			throw new IllegalArgumentException("Depreciation has already been charged up to " + depreciatedTo
					+ "; the disposal date must be after it.");
		BigDecimal proceeds = money(req.proceeds(), "Proceeds");
		if (req.proceedsAccountId() != null)
			gst.requireAccount(companyId, req.proceedsAccountId(), "ASSET");
		else if (proceeds.signum() > 0)
			throw new IllegalArgumentException("proceedsAccountId is required when proceeds are greater than zero.");
		poster.requireOpenPeriod(companyId, disposal);

		BigDecimal cost = (BigDecimal) a.get("cost");
		BigDecimal prior = (BigDecimal) a.get("accumulated_depreciation");
		LocalDate stubTo = disposal.minusDays(1);
		LocalDate stubFrom = nextDay(a);
		BigDecimal stub = stubFrom.isAfter(stubTo) ? BigDecimal.ZERO : Depreciation.chargeTo(toCalc(a), prior, stubTo);
		BigDecimal accumulated = prior.add(stub);
		BigDecimal gainLoss = proceeds.subtract(cost.subtract(accumulated));

		String label = "Disposal of fixed asset " + (a.get("asset_code") == null ? "#" + assetId : a.get("asset_code"))
				+ ": " + a.get("name");
		long accDep = poster.systemAccount(companyId, "ACCUMULATED_DEPRECIATION");
		List<LedgerPoster.Line> lines = new ArrayList<>();
		if (stub.signum() > 0) {
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "DEPRECIATION"),
					label + " (depreciation " + stubFrom + " to " + stubTo + ")", stub, BigDecimal.ZERO));
			lines.add(new LedgerPoster.Line(accDep, label + " (depreciation " + stubFrom + " to " + stubTo + ")",
					BigDecimal.ZERO, stub));
		}
		if (accumulated.signum() > 0)
			lines.add(new LedgerPoster.Line(accDep, label, accumulated, BigDecimal.ZERO));
		if (proceeds.signum() > 0)
			lines.add(new LedgerPoster.Line(req.proceedsAccountId(), label + " (proceeds)", proceeds, BigDecimal.ZERO));
		if (gainLoss.signum() < 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "ASSET_DISPOSAL_LOSS"), label + " (loss)",
					gainLoss.negate(), BigDecimal.ZERO));
		lines.add(new LedgerPoster.Line(((Number) a.get("asset_account_id")).longValue(), label + " (cost)",
				BigDecimal.ZERO, cost));
		if (gainLoss.signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "ASSET_DISPOSAL_GAIN"), label + " (gain)",
					BigDecimal.ZERO, gainLoss));
		long journalId = poster.createDraftJournal(companyId, disposal, label, "FA_DISPOSE:" + assetId, actor, lines);

		if (stub.signum() > 0)
			jdbc.update(
					"INSERT INTO fixed_asset_depreciation(company_id,asset_id,source,journal_entry_id,from_date,to_date,amount) VALUES (?,?,'DISPOSAL',?,?,?,?)",
					companyId, assetId, journalId, stubFrom, stubTo, stub);
		jdbc.update("""
				UPDATE fixed_assets SET status='DISPOSED',accumulated_depreciation=?,depreciated_to=?,disposal_date=?,
				 disposal_proceeds=?,proceeds_account_id=?,disposal_journal_id=?,disposal_gain_loss=?,disposed_at=now()
				WHERE company_id=? AND id=?
				""", accumulated, stub.signum() > 0 ? stubTo : depreciatedTo, disposal, proceeds, req.proceedsAccountId(),
				journalId, gainLoss, companyId, assetId);
		poster.audit(companyId, "FIXED_ASSET_DISPOSED", "FIXED_ASSET", assetId, "Proceeds " + proceeds.toPlainString()
				+ ", " + (gainLoss.signum() >= 0 ? "gain " : "loss ") + gainLoss.abs().toPlainString());
		return get(companyId, assetId);
	}

	private BigDecimal money(BigDecimal value, String label) {
		gst.checkAmount(value, 13, label);
		// NUMERIC(19,4) values carry scale 4, so compare significant digits rather than raw scale.
		if (value.stripTrailingZeros().scale() > 2)
			throw new IllegalArgumentException(label + " can have at most two decimal places.");
		return value.setScale(2, RoundingMode.UNNECESSARY);
	}

	private static LocalDate date(Map<String, Object> row, String column) {
		Object v = row.get(column);
		return v == null ? null : ((java.sql.Date) v).toLocalDate();
	}

	/** First day not yet depreciated. */
	private static LocalDate nextDay(Map<String, Object> a) {
		LocalDate depreciatedTo = date(a, "depreciated_to");
		return depreciatedTo == null ? date(a, "put_to_use_date") : depreciatedTo.plusDays(1);
	}

	private static Depreciation.Asset toCalc(Map<String, Object> a) {
		LocalDate openingTo = date(a, "opening_depreciated_to");
		return new Depreciation.Asset((BigDecimal) a.get("cost"), (BigDecimal) a.get("residual_value"),
				Depreciation.Method.valueOf((String) a.get("method")), ((Number) a.get("useful_life_years")).intValue(),
				(BigDecimal) a.get("wdv_rate"), openingTo == null ? date(a, "put_to_use_date") : openingTo.plusDays(1),
				(BigDecimal) a.get("opening_accumulated_depreciation"), date(a, "disposal_date"));
	}
}
