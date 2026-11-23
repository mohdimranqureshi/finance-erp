package com.financeerp.service;

import com.financeerp.domain.Depreciation;
import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.FixedAssetDto.CreateRequest;
import com.financeerp.dto.FixedAssetDto.DisposeRequest;
import com.financeerp.dto.FixedAssetDto.RunRequest;
import com.financeerp.repository.FixedAssetRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Fixed asset register with Schedule II style book depreciation. Capitalisation, depreciation runs and disposals create
 * DRAFT journals only; they reach the ledger through the maker-checker workflow.
 */
@Service
public class FixedAssetService {
	private static final Set<String> WRITERS = Set.of("OWNER", "ACCOUNTANT");
	private final FixedAssetRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public FixedAssetService(FixedAssetRepository repository, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public Map<String, Object> categories() {
		List<Map<String, Object>> categories = new ArrayList<>();
		Depreciation.CATEGORIES.forEach(c -> categories.add(Depreciation.describe(c)));
		return Map.of("categories", categories, "methods", List.of("SLM", "WDV"), "defaultResidualPercent", 5,
				"referenceData", true, "note", Depreciation.CATEGORY_NOTE);
	}

	@Transactional
	public Map<String, Object> create(long companyId, CreateRequest req) {
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
			Integer dup = repository.countAssetsWithCode(companyId, req.assetCode().trim());
			if (dup != null && dup > 0)
				throw new IllegalArgumentException("Asset code " + req.assetCode().trim() + " is already in use.");
		}
		if (req.sourceBillId() != null) {
			Integer bill = repository.countPurchaseBills(companyId, req.sourceBillId());
			if (bill == null || bill == 0)
				throw new IllegalArgumentException("Purchase bill not found.");
		}
		if (req.capitaliseFromAccountId() != null) {
			if (opening.signum() > 0 || openingTo != null)
				throw new IllegalArgumentException(
						"Brought-forward assets are recorded through opening balances; do not combine them with capitaliseFromAccountId.");
			if (req.capitaliseFromAccountId() == assetAccount)
				throw new IllegalArgumentException("capitaliseFromAccountId must differ from the asset account.");
			Integer n = repository.countActiveAccounts(companyId, req.capitaliseFromAccountId());
			if (n == null || n == 0)
				throw new IllegalArgumentException("Account " + req.capitaliseFromAccountId() + " must be an active account of this company.");
			if (req.sourceBillId() != null) {
				Integer used = repository.countBillLinesForExpenseAccount(companyId, req.sourceBillId(),
						req.capitaliseFromAccountId());
				if (used == null || used == 0)
					throw new IllegalArgumentException("The source bill has no line booked to capitaliseFromAccountId.");
			}
			poster.requireOpenPeriod(companyId, req.putToUseDate());
		}

		String code = req.assetCode() == null || req.assetCode().isBlank() ? null : req.assetCode().trim();
		Long id = repository.insertAsset(companyId, code, req.name().trim(), category.code(), cost, residual,
				req.putToUseDate(), life, method.name(), rate, assetAccount, req.sourceBillId(), opening, openingTo, actor);
		if (req.capitaliseFromAccountId() != null) {
			String label = "Capitalise fixed asset " + (code == null ? "#" + id : code) + ": " + req.name().trim();
			long journalId = poster.createDraftJournal(companyId, req.putToUseDate(), label, "FA_CAPITALISE:" + id, actor,
					List.of(new LedgerLine(assetAccount, label, cost, BigDecimal.ZERO),
							new LedgerLine(req.capitaliseFromAccountId(), label, BigDecimal.ZERO, cost)));
			repository.setCapitalisation(companyId, id, req.capitaliseFromAccountId(), journalId);
		}
		poster.audit(companyId, "FIXED_ASSET_CREATED", "FIXED_ASSET", id,
				method + " cost " + cost.toPlainString() + ", life " + life + " years");
		return get(companyId, id);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return repository.findAssets(companyId);
	}

	public Map<String, Object> get(long companyId, long assetId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findAsset(companyId, assetId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Fixed asset not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("depreciation", repository.findAssetDepreciation(companyId, assetId));
		return out;
	}

	@Transactional
	public Map<String, Object> run(long companyId, RunRequest req) {
		long actor = access.requireRole(companyId, WRITERS);
		LocalDate periodEnd = req.periodEnd();
		poster.requireOpenPeriod(companyId, periodEnd);
		// Locking the active assets serialises runs and disposals for this company.
		List<Map<String, Object>> assets = repository.lockActiveAssets(companyId);
		java.sql.Date last = repository.findLastRunPeriodEnd(companyId);
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
				List.of(new LedgerLine(poster.systemAccount(companyId, "DEPRECIATION"), label, total, BigDecimal.ZERO),
						new LedgerLine(poster.systemAccount(companyId, "ACCUMULATED_DEPRECIATION"), label,
								BigDecimal.ZERO, total)));
		Long runId = repository.insertRun(companyId, periodEnd, total, charges.size(), journalId, actor);
		for (Charge c : charges) {
			repository.insertRunDepreciation(companyId, c.assetId(), runId, journalId, c.from(), periodEnd, c.amount());
			repository.addAccumulatedDepreciation(companyId, c.assetId(), c.amount(), periodEnd);
		}
		poster.audit(companyId, "DEPRECIATION_RUN_CREATED", "DEPRECIATION_RUN", runId,
				"Up to " + periodEnd + ": " + total.toPlainString() + " across " + charges.size() + " assets");
		return getRun(companyId, runId);
	}

	public List<Map<String, Object>> runs(long companyId) {
		access.requireMembership(companyId);
		return repository.findRuns(companyId);
	}

	public Map<String, Object> getRun(long companyId, long runId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findRun(companyId, runId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Depreciation run not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", repository.findRunLines(companyId, runId));
		return out;
	}

	/** GST on the sale of a used asset is out of scope: raise a sales invoice separately if output tax is due. */
	@Transactional
	public Map<String, Object> dispose(long companyId, long assetId, DisposeRequest req) {
		long actor = access.requireRole(companyId, WRITERS);
		List<Map<String, Object>> rows = repository.lockAsset(companyId, assetId);
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
		List<LedgerLine> lines = new ArrayList<>();
		if (stub.signum() > 0) {
			lines.add(new LedgerLine(poster.systemAccount(companyId, "DEPRECIATION"),
					label + " (depreciation " + stubFrom + " to " + stubTo + ")", stub, BigDecimal.ZERO));
			lines.add(new LedgerLine(accDep, label + " (depreciation " + stubFrom + " to " + stubTo + ")",
					BigDecimal.ZERO, stub));
		}
		if (accumulated.signum() > 0)
			lines.add(new LedgerLine(accDep, label, accumulated, BigDecimal.ZERO));
		if (proceeds.signum() > 0)
			lines.add(new LedgerLine(req.proceedsAccountId(), label + " (proceeds)", proceeds, BigDecimal.ZERO));
		if (gainLoss.signum() < 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, "ASSET_DISPOSAL_LOSS"), label + " (loss)",
					gainLoss.negate(), BigDecimal.ZERO));
		lines.add(new LedgerLine(((Number) a.get("asset_account_id")).longValue(), label + " (cost)",
				BigDecimal.ZERO, cost));
		if (gainLoss.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, "ASSET_DISPOSAL_GAIN"), label + " (gain)",
					BigDecimal.ZERO, gainLoss));
		long journalId = poster.createDraftJournal(companyId, disposal, label, "FA_DISPOSE:" + assetId, actor, lines);

		if (stub.signum() > 0)
			repository.insertDisposalDepreciation(companyId, assetId, journalId, stubFrom, stubTo, stub);
		repository.markDisposed(companyId, assetId, accumulated, stub.signum() > 0 ? stubTo : depreciatedTo, disposal,
				proceeds, req.proceedsAccountId(), journalId, gainLoss);
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
