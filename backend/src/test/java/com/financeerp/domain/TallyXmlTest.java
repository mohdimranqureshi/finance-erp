package com.financeerp.domain;

import com.financeerp.domain.TallyGroups.Category;
import com.financeerp.domain.TallyXmlParser.*;
import com.financeerp.domain.TallyXmlWriter.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TallyXmlTest {
	static String sample() throws Exception {
		return Files.readString(Path.of("..", "samples", "tally-daybook-sample.xml"), StandardCharsets.UTF_8);
	}

	static byte[] utf16leWithBom(String s) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xFE);
		out.writeBytes(s.getBytes(StandardCharsets.UTF_16LE));
		return out.toByteArray();
	}

	private static Voucher voucher(TallyData d, String number) {
		return d.vouchers().stream().filter(v -> number.equals(v.number())).findFirst().orElseThrow();
	}

	@Test
	void parsesRealisticDaybookExport() throws Exception {
		TallyData d = TallyXmlParser.parse(sample().getBytes(StandardCharsets.UTF_8));
		assertThat(d.groups()).extracting(Group::name).contains("Current Assets", "Key Customers", "Office Expenses");
		assertThat(d.groups().get(0).parent()).as("&#4; before Primary is dropped").isEqualTo("Primary");
		assertThat(d.ledgers()).hasSize(12);
		Ledger acme = d.ledgers().get(0);
		assertThat(acme.name()).isEqualTo("Acme Traders");
		assertThat(acme.gstin()).isEqualTo("29AAPFU0939F1ZR");
		assertThat(acme.stateName()).isEqualTo("Karnataka");
		assertThat(acme.openingBalance()).isEqualByComparingTo("-25000");
		Ledger bharat = d.ledgers().get(1);
		assertThat(bharat.name()).as("name from NAME.LIST").isEqualTo("Bharat Supplies");
		assertThat(bharat.gstin()).isEqualTo("27ABCDE1234F1Z1");
		assertThat(bharat.stateName()).isEqualTo("Maharashtra");
		assertThat(bharat.openingBalance()).as("'4000.00 Cr' is a credit").isEqualByComparingTo("4000");
		assertThat(d.ledgers()).extracting(Ledger::name).contains("Sales @18%");

		assertThat(d.vouchers()).hasSize(10);
		Voucher sale = voucher(d, "S/001");
		assertThat(sale.date()).isEqualTo(LocalDate.of(2024, 4, 5));
		assertThat(sale.guid()).isEqualTo("4f9c2a10-1d2e-4b3c-9f00-6a7b8c9d0e1f-00000001");
		assertThat(sale.narration()).isEqualTo("Consulting & support for April");
		assertThat(sale.entries()).extracting(Entry::ledgerName)
				.containsExactly("Acme Traders", "IGST Output", "Sales @18%");
		assertThat(sale.total()).isEqualByComparingTo("0");
		assertThat(voucher(d, "P/014").entries().get(0).amount()).isEqualByComparingTo("-5000");
		assertThat(voucher(d, "PY/004").cancelled()).isTrue();
		assertThat(voucher(d, "GT/001").entries()).isEmpty();
		assertThat(voucher(d, "J/001").total()).isEqualByComparingTo("-100");
		assertThat(d.voucherTypes()).containsExactly(new VoucherType("Stock Transfer", "Stock Journal"));
	}

	@Test
	void utf16LittleEndianWithBomParsesLikeUtf8() throws Exception {
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n" + sample().replace("Acme Traders", "Acmé Traders ₹");
		TallyData d = TallyXmlParser.parse(utf16leWithBom(xml));
		assertThat(d.ledgers().get(0).name()).isEqualTo("Acmé Traders ₹");
		assertThat(d.vouchers()).hasSize(10);
		assertThat(voucher(d, "S/001").entries().get(0).ledgerName()).isEqualTo("Acmé Traders ₹");

		byte[] utf8Bom = ("\uFEFF" + sample()).getBytes(StandardCharsets.UTF_8);
		assertThat(TallyXmlParser.parse(utf8Bom).vouchers()).hasSize(10);
	}

	@Test
	void signConventionNegativeIsDebitAndDeemedPositiveAgrees() throws Exception {
		TallyData d = TallyXmlParser.parse(sample());
		for (Voucher v : d.vouchers())
			for (Entry e : v.entries())
				if (e.amount().signum() != 0 && e.deemedPositive() != null)
					assertThat(e.debit()).as(v.number() + " " + e.ledgerName()).isEqualTo(e.deemedPositive());
		Voucher receipt = voucher(d, "R/007");
		assertThat(receipt.entries().get(0).debit()).as("'5000.00 Dr' is a debit").isTrue();
		assertThat(receipt.entries().get(0).amount()).isEqualByComparingTo("-5000");
		assertThat(receipt.entries().get(1).debit()).isFalse();
		assertThat(receipt.debits()).isEqualByComparingTo(receipt.credits());
		assertThat(d.warnings()).isEmpty();

		TallyData mismatch = TallyXmlParser.parse("<ENVELOPE><BODY><VOUCHER><DATE>20240401</DATE><VOUCHERTYPENAME>Journal</VOUCHERTYPENAME>"
				+ "<ALLLEDGERENTRIES.LIST><LEDGERNAME>A</LEDGERNAME><ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE><AMOUNT>-10</AMOUNT></ALLLEDGERENTRIES.LIST>"
				+ "<ALLLEDGERENTRIES.LIST><LEDGERNAME>B</LEDGERNAME><ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE><AMOUNT>10</AMOUNT></ALLLEDGERENTRIES.LIST>"
				+ "</VOUCHER></BODY></ENVELOPE>");
		assertThat(mismatch.vouchers().get(0).entries().get(0).debit()).as("AMOUNT sign wins").isTrue();
		assertThat(mismatch.warnings()).singleElement().asString().contains("ISDEEMEDPOSITIVE disagrees");
	}

	@Test
	void amountsAreParsedTolerantly() {
		assertThat(TallyXmlParser.parseAmount("-1,00,000.00")).isEqualByComparingTo("-100000");
		assertThat(TallyXmlParser.parseAmount("1000.00 Dr")).isEqualByComparingTo("-1000");
		assertThat(TallyXmlParser.parseAmount("-1000.00 Cr")).isEqualByComparingTo("1000");
		assertThat(TallyXmlParser.parseAmount("₹ 250.50")).isEqualByComparingTo("250.50");
		assertThat(TallyXmlParser.parseAmount("Rs. 75")).isEqualByComparingTo("75");
		assertThat(TallyXmlParser.parseAmount("(40.00)")).isEqualByComparingTo("-40");
		assertThat(TallyXmlParser.parseAmount("-$100.00 @ ₹ 80.00/$ = -₹ 8000.00")).isEqualByComparingTo("-8000");
		assertThat(TallyXmlParser.parseAmount("  ")).isNull();
		assertThatThrownBy(() -> TallyXmlParser.parseAmount("abc")).isInstanceOf(IllegalArgumentException.class);
		assertThat(TallyXmlParser.parseDate("20240229")).isEqualTo(LocalDate.of(2024, 2, 29));
		assertThat(TallyXmlParser.parseDate("1-Apr-2024")).isEqualTo(LocalDate.of(2024, 4, 1));
		assertThat(TallyXmlParser.parseDate("20230229")).isNull();
	}

	@Test
	void unreadableVoucherIsFlaggedNotDropped() {
		TallyData d = TallyXmlParser.parse("<ENVELOPE><VOUCHER><DATE>2024-13-45</DATE><VOUCHERTYPENAME>Journal</VOUCHERTYPENAME>"
				+ "<ALLLEDGERENTRIES.LIST><LEDGERNAME>A</LEDGERNAME><AMOUNT>oops</AMOUNT></ALLLEDGERENTRIES.LIST></VOUCHER></ENVELOPE>");
		assertThat(d.vouchers().get(0).parseError()).contains("DATE").contains("AMOUNT");
	}

	@Test
	void rejectsDtdExternalEntitiesAndNonTallyXml() {
		String xxe = "<?xml version=\"1.0\"?><!DOCTYPE ENVELOPE [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
				+ "<ENVELOPE><LEDGER NAME=\"&xxe;\"><PARENT>Sundry Debtors</PARENT></LEDGER></ENVELOPE>";
		assertThatThrownBy(() -> TallyXmlParser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
		assertThatThrownBy(() -> TallyXmlParser.parse(utf16leWithBom(xxe))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TallyXmlParser.parse("<ENVELOPE><LEDGER NAME=\"&xxe;\"/></ENVELOPE>"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TallyXmlParser.parse("<html><body/></html>"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ENVELOPE");
		assertThatThrownBy(() -> TallyXmlParser.parse("<ENVELOPE><LEDGER>")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TallyXmlParser.parse(new byte[(int) TallyXmlParser.MAX_BYTES + 1]))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10 MB");
	}

	@Test
	void enforcesVoucherCountLimit() {
		StringBuilder b = new StringBuilder("<ENVELOPE>");
		for (int i = 0; i <= TallyXmlParser.MAX_VOUCHERS; i++)
			b.append("<VOUCHER/>");
		b.append("</ENVELOPE>");
		assertThatThrownBy(() -> TallyXmlParser.parse(b.toString())).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("vouchers");
	}

	@Test
	void resolvesGroupChainsToCategories() throws Exception {
		TallyData d = TallyXmlParser.parse(sample());
		Map<String, Group> groups = TallyGroups.index(d.groups());
		assertThat(TallyGroups.resolve("Key Customers", groups).category()).isEqualTo(Category.CUSTOMER);
		assertThat(TallyGroups.resolve("Key Customers", groups).primaryGroup()).isEqualTo("Sundry Debtors");
		assertThat(TallyGroups.resolve("Office Expenses", groups).category()).isEqualTo(Category.EXPENSE);
		assertThat(TallyGroups.resolve("Sundry Creditors", groups).category()).isEqualTo(Category.VENDOR);
		assertThat(TallyGroups.resolve("Duties & Taxes", groups).category()).isEqualTo(Category.LIABILITY);
		assertThat(TallyGroups.resolve("cash in hand", groups).category()).isEqualTo(Category.ASSET);
		assertThat(TallyGroups.resolve("Bank OCC A/c", groups).primaryGroup()).isEqualTo("Bank OD A/c");
		assertThat(TallyGroups.resolve("Suspense A/c", groups).category()).isEqualTo(Category.MANUAL);
		assertThat(TallyGroups.resolve("Branch/Divisions", groups).category()).isEqualTo(Category.MANUAL);
		assertThat(TallyGroups.resolve("Unknown Group", groups).category()).isEqualTo(Category.MANUAL);
		assertThat(TallyGroups.predefinedGroups()).hasSize(28);

		List<Group> custom = List.of(new Group("Debtors", "Current Assets", "Sundry Debtors", null, null),
				new Group("North Debtors", "Debtors", null, null, null), new Group("Loop A", "Loop B", null, null, null),
				new Group("Loop B", "Loop A", null, null, null),
				new Group("Project Income", "Primary", null, true, false),
				new Group("Site Costs", "Project Income Sub", null, null, null),
				new Group("Project Income Sub", "Project Income", null, null, null));
		Map<String, Group> idx = TallyGroups.index(custom);
		assertThat(TallyGroups.resolve("North Debtors", idx).category()).as("renamed predefined group")
				.isEqualTo(Category.CUSTOMER);
		assertThat(TallyGroups.resolve("Loop A", idx).note()).contains("circular");
		assertThat(TallyGroups.resolve("Site Costs", idx).category()).as("user primary group nature")
				.isEqualTo(Category.INCOME);
	}

	@Test
	void exportParsesBackWithSameSignsAndEscaping() {
		Map<Long, String> names = TallyXmlWriter.ledgerNames(List.of(new AccountRef(1, "1010", "Bank Accounts"),
				new AccountRef(2, "4000", "Sales <Domestic> & \"Export\""), new AccountRef(3, "5200", "Rent"),
				new AccountRef(4, "5201", "rent")));
		assertThat(names.get(1L)).isEqualTo("Bank Accounts (1010)");
		assertThat(names.get(3L)).isEqualTo("Rent (5200)");
		assertThat(names.get(4L)).isEqualTo("rent (5201)");
		List<LedgerOut> ledgers = List.of(new LedgerOut(names.get(1L), "Bank Accounts"),
				new LedgerOut(names.get(2L), "Sales Accounts"), new LedgerOut(names.get(3L), "Indirect Expenses"));
		List<VoucherOut> vouchers = List.of(
				new VoucherOut("17", LocalDate.of(2024, 4, 1), "Cash sale 'A' & <B>\u0001",
						List.of(new LineOut(names.get(1L), new BigDecimal("1180.0000"), BigDecimal.ZERO),
								new LineOut(names.get(2L), BigDecimal.ZERO, new BigDecimal("1180.0000")))),
				new VoucherOut("18", LocalDate.of(2024, 4, 2), null,
						List.of(new LineOut(names.get(3L), new BigDecimal("0.1250"), BigDecimal.ZERO),
								new LineOut(names.get(1L), BigDecimal.ZERO, new BigDecimal("0.1250")))));
		String xml = TallyXmlWriter.write(ledgers, vouchers);
		assertThat(xml).contains("<TALLYREQUEST>Import Data</TALLYREQUEST>", "<REPORTNAME>Vouchers</REPORTNAME>",
				"<AMOUNT>-1180.00</AMOUNT>", "<AMOUNT>0.125</AMOUNT>");

		TallyData d = TallyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
		assertThat(d.ledgers()).extracting(Ledger::name).containsExactly(names.get(1L), names.get(2L), names.get(3L));
		assertThat(d.ledgers()).extracting(Ledger::parent).containsExactly("Bank Accounts", "Sales Accounts",
				"Indirect Expenses");
		assertThat(d.vouchers()).hasSize(2);
		Voucher v = d.vouchers().get(0);
		assertThat(v.voucherType()).isEqualTo("Journal");
		assertThat(v.number()).isEqualTo("17");
		assertThat(v.date()).isEqualTo(LocalDate.of(2024, 4, 1));
		assertThat(v.narration()).isEqualTo("Cash sale 'A' & <B>");
		assertThat(v.entries().get(0).ledgerName()).isEqualTo("Bank Accounts (1010)");
		assertThat(v.entries().get(0).debit()).isTrue();
		assertThat(v.entries().get(0).deemedPositive()).isTrue();
		assertThat(v.entries().get(1).ledgerName()).isEqualTo("Sales <Domestic> & \"Export\"");
		assertThat(v.entries().get(1).amount()).isEqualByComparingTo("1180");
		assertThat(v.total()).isEqualByComparingTo("0");
		assertThat(d.vouchers().get(1).entries().get(0).amount()).isEqualByComparingTo("-0.125");
		assertThat(d.warnings()).isEmpty();
	}
}
