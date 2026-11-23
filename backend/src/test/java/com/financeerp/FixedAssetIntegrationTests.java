package com.financeerp;

import com.financeerp.domain.Depreciation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FixedAssetIntegrationTests extends IntegrationTestSupport {
	private static BigDecimal dec(Object value) {
		return new BigDecimal(value.toString());
	}

	/** Net debit of an account code across the given journal (status ignored). */
	private BigDecimal journalNet(TestCompany c, long journalId, String code) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
				WHERE l.company_id=? AND l.journal_entry_id=? AND a.code=?
				""", BigDecimal.class, c.id(), journalId, code);
	}

	private BigDecimal postedBalance(TestCompany c, String code) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.debit-l.credit),0) FROM journal_lines l
				JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
				JOIN journal_entries j ON j.company_id=l.company_id AND j.id=l.journal_entry_id AND j.status='POSTED'
				WHERE l.company_id=? AND a.code=?
				""", BigDecimal.class, c.id(), code);
	}

	@Test
	void assetLifecycleCapitaliseDepreciateAndDisposeThroughDraftJournals() {
		TestCompany c = indiaCompany("Fixed Asset Co");
		LocalDate today = today();
		LocalDate fyStart = LocalDate.of(today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1, 4, 1);
		String fa = c.url() + "/fixed-assets";

		Map<String, Object> categories = get(base() + "/api/v1/fixed-assets/categories", c.owner());
		assertThat((List<?>) categories.get("categories")).isNotEmpty();
		assertThat(categories.get("referenceData")).isEqualTo(true);

		// Laptops bought on a purchase bill (expense line), then capitalised by a reclassification journal.
		long vendorId = vendor(c, "Laptop Vendor", "29AAPFU0939F1ZR", "29");
		long otherExpenses = accountId(c, "5900");
		long billId = number(post(c.url() + "/purchase-bills", Map.of("vendorId", vendorId, "vendorInvoiceNumber", "LAP-1",
				"billDate", fyStart.toString(), "lines", List.of(Map.of("description", "Laptops", "hsnSac", "8471",
						"quantity", 4, "unitPrice", 30000, "gstRate", 18, "expenseAccountId", otherExpenses))),
				c.owner(), HttpStatus.OK).get("id"));

		Map<String, Object> laptopRequest = new HashMap<>(Map.of("name", "Laptops", "category", "COMPUTER", "assetCode",
				"IT-001", "cost", 120000, "putToUseDate", fyStart.toString(), "method", "SLM", "capitaliseFromAccountId",
				otherExpenses, "sourceBillId", billId));
		post(fa, laptopRequest, c.approver(), HttpStatus.FORBIDDEN);
		Map<String, Object> laptops = post(fa, laptopRequest, c.owner(), HttpStatus.OK);
		long laptopId = number(laptops.get("id"));
		assertThat(dec(laptops.get("residualValue"))).isEqualByComparingTo("6000");
		assertThat(number(laptops.get("usefulLifeYears"))).isEqualTo(3);
		assertThat(laptops.get("status")).isEqualTo("ACTIVE");
		assertThat(dec(laptops.get("netBookValue"))).isEqualByComparingTo("120000");
		assertThat(laptops.get("capitalisationJournalStatus")).isEqualTo("DRAFT");
		long capitalisation = number(laptops.get("capitalisationJournalId"));
		assertThat(journalNet(c, capitalisation, "1500")).isEqualByComparingTo("120000");
		assertThat(journalNet(c, capitalisation, "5900")).isEqualByComparingTo("-120000");
		approveAndPost(c, capitalisation);

		post(fa, laptopRequest, c.owner(), HttpStatus.BAD_REQUEST); // duplicate asset code
		post(fa, Map.of("name", "Bad", "category", "COMPUTER", "cost", 1000, "putToUseDate", fyStart.toString(), "method",
				"SLM", "assetAccountId", otherExpenses), c.owner(), HttpStatus.BAD_REQUEST);
		post(fa, Map.of("name", "Bad", "category", "NOPE", "cost", 1000, "putToUseDate", fyStart.toString(), "method", "SLM"),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(fa, Map.of("name", "Bad", "category", "OTHER", "cost", 1000, "putToUseDate", fyStart.toString(), "method", "SLM"),
				c.owner(), HttpStatus.BAD_REQUEST);

		// Brought-forward furniture: opening depreciation lives in opening balances, so no journal is drafted.
		Map<String, Object> furniture = post(fa, Map.of("name", "Office furniture", "category", "FURNITURE", "cost", 50000,
				"putToUseDate", fyStart.minusYears(2).toString(), "method", "WDV", "openingAccumulatedDepreciation", 10000,
				"openingDepreciatedTo", fyStart.minusDays(1).toString()), c.owner(), HttpStatus.OK);
		long furnitureId = number(furniture.get("id"));
		assertThat(furniture.get("capitalisationJournalId")).isNull();
		assertThat(dec(furniture.get("netBookValue"))).isEqualByComparingTo("40000");
		BigDecimal furnitureRate = dec(furniture.get("wdvRate"));
		assertThat(furnitureRate).isEqualByComparingTo(Depreciation.wdvRate(new BigDecimal("50000"), new BigDecimal("2500"), 10));

		LocalDate periodEnd = fyStart.plusMonths(6).minusDays(1);
		Depreciation.Asset laptopCalc = new Depreciation.Asset(new BigDecimal("120000"), new BigDecimal("6000"),
				Depreciation.Method.SLM, 3, null, fyStart, null, null);
		Depreciation.Asset furnitureCalc = new Depreciation.Asset(new BigDecimal("50000"), new BigDecimal("2500"),
				Depreciation.Method.WDV, 10, furnitureRate, fyStart, new BigDecimal("10000"), null);
		BigDecimal laptopCharge = Depreciation.accumulatedTo(laptopCalc, periodEnd);
		BigDecimal furnitureCharge = Depreciation.accumulatedTo(furnitureCalc, periodEnd).subtract(new BigDecimal("10000"));
		assertThat(laptopCharge).isPositive();
		assertThat(furnitureCharge).isPositive();

		String runs = fa + "/depreciation-runs";
		Map<String, Object> run = post(runs, Map.of("periodEnd", periodEnd.toString()), c.owner(), HttpStatus.OK);
		assertThat(dec(run.get("totalAmount"))).isEqualByComparingTo(laptopCharge.add(furnitureCharge));
		assertThat(run.get("journalStatus")).isEqualTo("DRAFT");
		assertThat((List<?>) run.get("lines")).hasSize(2);
		long runJournal = number(run.get("journalEntryId"));
		assertThat(journalNet(c, runJournal, "5400")).isEqualByComparingTo(laptopCharge.add(furnitureCharge));
		assertThat(journalNet(c, runJournal, "1590")).isEqualByComparingTo(laptopCharge.add(furnitureCharge).negate());
		post(runs, Map.of("periodEnd", periodEnd.toString()), c.owner(), HttpStatus.BAD_REQUEST);
		post(runs, Map.of("periodEnd", periodEnd.minusMonths(1).toString()), c.owner(), HttpStatus.BAD_REQUEST);
		post(runs, Map.of("periodEnd", fyStart.plusYears(1).plusDays(5).toString()), c.owner(), HttpStatus.BAD_REQUEST);
		assertThat(getList(runs, c.owner())).hasSize(1);
		approveAndPost(c, runJournal);

		Map<String, Object> afterRun = get(fa + "/" + laptopId, c.owner());
		assertThat(dec(afterRun.get("accumulatedDepreciation"))).isEqualByComparingTo(laptopCharge);
		assertThat(afterRun.get("depreciatedTo")).isEqualTo(periodEnd.toString());
		assertThat((List<?>) afterRun.get("depreciation")).hasSize(1);

		// Disposal mid-period: the stub depreciation since the last run goes into the disposal journal.
		LocalDate disposalDate = periodEnd.plusDays(46);
		String disposeUrl = fa + "/" + laptopId + "/dispose";
		long bank = accountId(c, "1010");
		post(disposeUrl, Map.of("disposalDate", disposalDate.toString(), "proceeds", 70000), c.owner(), HttpStatus.BAD_REQUEST);
		post(disposeUrl, Map.of("disposalDate", disposalDate.toString(), "proceeds", 70000, "proceedsAccountId", otherExpenses),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(fa + "/" + furnitureId + "/dispose", Map.of("disposalDate", periodEnd.toString(), "proceeds", 0), c.owner(),
				HttpStatus.BAD_REQUEST);
		Map<String, Object> disposed = post(disposeUrl, Map.of("disposalDate", disposalDate.toString(), "proceeds", 70000,
				"proceedsAccountId", bank), c.owner(), HttpStatus.OK);
		BigDecimal totalAccumulated = Depreciation.accumulatedTo(laptopCalc, disposalDate.minusDays(1));
		BigDecimal stub = totalAccumulated.subtract(laptopCharge);
		BigDecimal gainLoss = new BigDecimal("70000").subtract(new BigDecimal("120000").subtract(totalAccumulated));
		assertThat(stub).isPositive();
		assertThat(gainLoss).isNegative();
		assertThat(disposed.get("status")).isEqualTo("DISPOSED");
		assertThat(dec(disposed.get("accumulatedDepreciation"))).isEqualByComparingTo(totalAccumulated);
		assertThat(dec(disposed.get("disposalGainLoss"))).isEqualByComparingTo(gainLoss);
		assertThat(dec(disposed.get("netBookValue"))).isEqualByComparingTo("0");
		List<?> history = (List<?>) disposed.get("depreciation");
		assertThat(history).hasSize(2);
		assertThat(((Map<?, ?>) history.get(1)).get("source")).isEqualTo("DISPOSAL");

		long disposalJournal = number(disposed.get("disposalJournalId"));
		assertThat(journalNet(c, disposalJournal, "5400")).isEqualByComparingTo(stub);
		assertThat(journalNet(c, disposalJournal, "1590")).isEqualByComparingTo(totalAccumulated.subtract(stub));
		assertThat(journalNet(c, disposalJournal, "1010")).isEqualByComparingTo("70000");
		assertThat(journalNet(c, disposalJournal, "1500")).isEqualByComparingTo("-120000");
		assertThat(journalNet(c, disposalJournal, "5910")).isEqualByComparingTo(gainLoss.negate());
		assertThat(journalNet(c, disposalJournal, "4910")).isEqualByComparingTo("0");
		approveAndPost(c, disposalJournal);
		post(disposeUrl, Map.of("disposalDate", disposalDate.plusDays(1).toString(), "proceeds", 0), c.owner(),
				HttpStatus.BAD_REQUEST);

		// Laptops are fully out of the books; only the furniture's in-year depreciation remains on 1590.
		assertThat(postedBalance(c, "1500")).isEqualByComparingTo("0");
		assertThat(postedBalance(c, "1590")).isEqualByComparingTo(furnitureCharge.negate());

		List<Map<String, Object>> register = getList(fa, c.owner());
		assertThat(register).hasSize(2);
		assertThat(register).extracting(r -> r.get("status")).containsExactlyInAnyOrder("DISPOSED", "ACTIVE");
		Integer audits = jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE company_id=? AND action IN ('FIXED_ASSET_CREATED','DEPRECIATION_RUN_CREATED','FIXED_ASSET_DISPOSED')",
				Integer.class, c.id());
		assertThat(audits).isEqualTo(4);
	}

	@Test
	void disposalAtAGainCreditsTheGainAccount() {
		TestCompany c = indiaCompany("Fixed Asset Gain Co");
		LocalDate today = today();
		LocalDate fyStart = LocalDate.of(today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1, 4, 1);
		Map<String, Object> asset = post(c.url() + "/fixed-assets", Map.of("name", "Delivery van", "category",
				"MOTOR_VEHICLE", "cost", 800000, "putToUseDate", fyStart.minusYears(3).toString(), "method", "SLM",
				"openingAccumulatedDepreciation", 285000, "openingDepreciatedTo", fyStart.minusDays(1).toString()),
				c.owner(), HttpStatus.OK);
		// Disposed on the first day of the year: no stub, NBV 515000, sold for 600000.
		Map<String, Object> disposed = post(c.url() + "/fixed-assets/" + number(asset.get("id")) + "/dispose",
				Map.of("disposalDate", fyStart.toString(), "proceeds", 600000, "proceedsAccountId", accountId(c, "1100")),
				c.owner(), HttpStatus.OK);
		assertThat(dec(disposed.get("disposalGainLoss"))).isEqualByComparingTo("85000");
		long journal = number(disposed.get("disposalJournalId"));
		assertThat(journalNet(c, journal, "4910")).isEqualByComparingTo("-85000");
		assertThat(journalNet(c, journal, "1590")).isEqualByComparingTo("285000");
		assertThat(journalNet(c, journal, "1100")).isEqualByComparingTo("600000");
		assertThat(journalNet(c, journal, "5400")).isEqualByComparingTo("0");
		assertThat((List<?>) disposed.get("depreciation")).isEmpty();
	}
}
