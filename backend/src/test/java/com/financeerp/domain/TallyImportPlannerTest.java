package com.financeerp.domain;

import com.financeerp.service.LedgerPoster;
import com.financeerp.domain.TallyImportPlanner.*;
import com.financeerp.domain.TallyXmlParser.TallyData;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TallyImportPlannerTest {
	private static final long AR = 11, AP = 12;

	private static Context context(Map<String, Long> imported) {
		return new Context(
				List.of(new ExistingAccount(AR, "1100", "Trade Receivables", "ASSET"),
						new ExistingAccount(AP, "2000", "Trade Payables", "LIABILITY"),
						new ExistingAccount(20, "5200", "Office Rent", "EXPENSE"),
						new ExistingAccount(21, "TLY-1-001", "Something Else", "ASSET")),
				List.of(), imported, "27", AR, AP);
	}

	private static TallyData data() throws Exception {
		return TallyXmlParser.parse(TallyXmlTest.sample());
	}

	private static VoucherPlan voucher(Plan p, String number) {
		return p.vouchers.stream().filter(v -> number.equals(v.voucher().number())).findFirst().orElseThrow();
	}

	@Test
	void mapsLedgersAndClassifiesVouchers() throws Exception {
		Plan p = TallyImportPlanner.plan(data(), context(Map.of()), Map.of());

		LedgerPlan acme = p.ledger("acme traders");
		assertThat(acme.action).isEqualTo(TallyImportPlanner.NEW_PARTY);
		assertThat(acme.party.partyType).isEqualTo("CUSTOMER");
		assertThat(acme.party.gstin).isEqualTo("29AAPFU0939F1ZR");
		assertThat(acme.party.stateCode).isEqualTo("29");
		assertThat(acme.accountId).isEqualTo(AR);
		LedgerPlan bharat = p.ledger("Bharat Supplies");
		assertThat(bharat.party.partyType).isEqualTo("VENDOR");
		assertThat(bharat.party.gstin).as("invalid GSTIN dropped").isNull();
		assertThat(bharat.party.stateCode).as("state from mailing details").isEqualTo("27");
		assertThat(bharat.warnings).anyMatch(w -> w.contains("invalid"));
		assertThat(bharat.accountId).isEqualTo(AP);
		assertThat(p.ledger("Office Rent").action).as("matched by name").isEqualTo(TallyImportPlanner.MATCHED_ACCOUNT);
		assertThat(p.ledger("Office Rent").accountId).isEqualTo(20L);
		assertThat(p.ledger("Sales @18%").accountType).isEqualTo("INCOME");
		assertThat(p.ledger("IGST Output").accountType).isEqualTo("LIABILITY");
		assertThat(p.ledger("Partner Capital").accountType).isEqualTo("EQUITY");
		assertThat(p.ledger("Cash").accountCode).as("existing code skipped").isEqualTo("TLY-1-002");
		assertThat(p.ledger("HDFC Bank").accountCode).isEqualTo("TLY-1-003");
		assertThat(p.ledger("Suspense").action).isEqualTo(TallyImportPlanner.UNMAPPED);
		assertThat(p.newAccounts).hasSize(8);
		assertThat(p.newParties).hasSize(2);

		assertThat(voucher(p, "S/001").status()).isEqualTo(Status.IMPORT);
		assertThat(voucher(p, "S/001").key()).isEqualTo("TALLY:4f9c2a10-1d2e-4b3c-9f00-6a7b8c9d0e1f-00000001");
		assertThat(voucher(p, "PY/003").key()).startsWith("TALLY:").hasSize(6 + 64);
		assertThat(voucher(p, "P/014").status()).isEqualTo(Status.IMPORT);
		assertThat(voucher(p, "R/007").status()).isEqualTo(Status.IMPORT);
		assertThat(voucher(p, "PY/003").status()).isEqualTo(Status.IMPORT);
		assertThat(voucher(p, "PY/004").reason()).contains("cancelled");
		assertThat(voucher(p, "J/001").status()).isEqualTo(Status.FAIL);
		assertThat(voucher(p, "J/001").reason()).startsWith("unbalanced");
		assertThat(voucher(p, "J/002").reason()).contains("unmapped ledger(s): Suspense");
		assertThat(voucher(p, "DN/001").reason()).contains("non-accounting");
		assertThat(voucher(p, "ST/001").reason()).as("custom type under Stock Journal").contains("non-accounting");
		assertThat(voucher(p, "GT/001").reason()).startsWith("inventory-only");
		assertThat(p.problems).extracting(m -> m.get("code")).contains("UNBALANCED_VOUCHER", "UNMAPPED_LEDGER",
				"INVENTORY_ONLY_SKIPPED", "INVALID_GSTIN");

		Map<String, Object> preview = p.preview();
		Map<?, ?> summary = (Map<?, ?>) preview.get("summary");
		assertThat(summary.get("importable")).isEqualTo(4L);
		assertThat(summary.get("failed")).isEqualTo(2L);
		assertThat(summary.get("skipped")).isEqualTo(4L);
		Map<?, ?> opening = (Map<?, ?>) preview.get("openingBalances");
		assertThat((BigDecimal) opening.get("totalDebit")).isEqualByComparingTo("125000");
		assertThat((BigDecimal) opening.get("totalCredit")).isEqualByComparingTo("129000");
		Map<?, ?> vouchers = (Map<?, ?>) preview.get("vouchers");
		assertThat(vouchers.get("from")).hasToString("2024-04-05");
		assertThat(vouchers.get("to")).hasToString("2024-04-30");
	}

	@Test
	void journalLinesFollowTallySignAndNameParty() throws Exception {
		Plan p = TallyImportPlanner.plan(data(), context(Map.of()), Map.of());
		long next = 100;
		for (LedgerPlan lp : p.newAccounts)
			lp.accountId = next++;
		List<LedgerLine> lines = p.lines(voucher(p, "S/001"));
		assertThat(lines).hasSize(3);
		LedgerLine party = lines.get(0);
		assertThat(party.accountId()).isEqualTo(AR);
		assertThat(party.description()).isEqualTo("Acme Traders");
		assertThat(party.debit()).isEqualByComparingTo("11800");
		assertThat(party.credit()).isEqualByComparingTo("0");
		assertThat(lines.get(1).credit()).isEqualByComparingTo("1800");
		assertThat(lines.get(2).credit()).isEqualByComparingTo("10000");
		assertThat(lines.get(2).accountId()).isEqualTo(p.ledger("Sales @18%").accountId);
		assertThat(voucher(p, "S/001").description()).isEqualTo("Tally Sales #S/001: Consulting & support for April");
	}

	@Test
	void overridesAndReimportSkipping() throws Exception {
		TallyData d = data();
		Plan first = TallyImportPlanner.plan(d, context(Map.of()), Map.of());
		String saleKey = voucher(first, "S/001").key();
		String rentKey = voucher(first, "PY/003").key();
		Plan again = TallyImportPlanner.plan(d, context(Map.of(saleKey, 501L, rentKey, 502L)),
				Map.of("suspense", 20, "Cash", "expense", "HDFC Bank", "21", "Partner Capital", "VENDOR", "Nope", "ASSET"));
		assertThat(voucher(again, "S/001").reason()).isEqualTo("already imported as journal 501");
		assertThat(voucher(again, "PY/003").status()).isEqualTo(Status.SKIP);
		assertThat(voucher(again, "J/002").status()).isEqualTo(Status.IMPORT);
		assertThat(again.ledger("Suspense").accountId).isEqualTo(20L);
		assertThat(again.ledger("Cash").accountType).isEqualTo("EXPENSE");
		assertThat(again.ledger("HDFC Bank").accountId).isEqualTo(21L);
		assertThat(again.ledger("Partner Capital").party.partyType).isEqualTo("VENDOR");
		assertThat(again.warnings).anyMatch(w -> w.contains("'Nope'"));
		assertThatThrownBy(() -> TallyImportPlanner.plan(d, context(Map.of()), Map.of("Cash", 999)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("999");
		assertThatThrownBy(() -> TallyImportPlanner.plan(d, context(Map.of()), Map.of("Cash", "BANANA")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void partiesNeedControlAccountsAndKeysFitColumn() throws Exception {
		Context noChart = new Context(List.of(), List.of(), Map.of(), "27", null, null);
		Plan p = TallyImportPlanner.plan(data(), noChart, Map.of());
		assertThat(p.ledger("Acme Traders").action).isEqualTo(TallyImportPlanner.UNMAPPED);
		assertThat(p.ledger("Acme Traders").reason).contains("AR");
		assertThat(voucher(p, "S/001").status()).isEqualTo(Status.FAIL);

		String longGuid = "x".repeat(150);
		assertThat(TallyImportPlanner.idempotencyKey(longGuid)).startsWith("TALLY:SHA256:").hasSizeLessThanOrEqualTo(100);
		assertThat(TallyImportPlanner.idempotencyKey(longGuid)).isNotEqualTo(TallyImportPlanner.idempotencyKey(longGuid + "y"));
	}
}
