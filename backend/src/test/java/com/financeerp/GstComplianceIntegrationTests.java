package com.financeerp;

import com.financeerp.domain.IndiaTax;
import com.financeerp.domain.TdsRules;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class GstComplianceIntegrationTests extends IntegrationTestSupport {
	@Test
	void reverseChargeTcsGstr3bAndTdsChallansWorkTogether() {
		TestCompany c = indiaCompany("Compliance Co");
		LocalDate today = today();
		// Unregistered advocate in the same state: legal services under reverse charge.
		long advocate = number(post(c.url() + "/parties", Map.of("partyType", "VENDOR", "name", "Advocate", "stateCode", "27"),
				c.owner(), HttpStatus.OK).get("id"));
		long consultant = vendor(c, "Consultants", "29AAPFU0939F1ZR", "29");
		long buyer = number(post(c.url() + "/parties", Map.of("partyType", "CUSTOMER", "name", "Scrap Buyer", "stateCode", "27"),
				c.owner(), HttpStatus.OK).get("id"));

		Map<String, Object> rcmBill = post(c.url() + "/purchase-bills", Map.of("vendorId", advocate, "vendorInvoiceNumber", "ADV-1",
				"billDate", today.toString(), "reverseCharge", true,
				"lines", List.of(Map.of("description", "Legal fees", "hsnSac", "998212", "quantity", 1, "unitPrice", 10000, "gstRate", 18))),
				c.owner(), HttpStatus.OK);
		assertThat(rcmBill.get("reverseCharge")).isEqualTo(true);
		assertThat(new BigDecimal(rcmBill.get("totalAmount").toString())).isEqualByComparingTo("10000");
		long rcmBillId = number(rcmBill.get("id"));
		approveAndPost(c, number(post(c.url() + "/purchase-bills/" + rcmBillId + "/book", Map.of(), c.owner(), HttpStatus.OK).get("journalEntryId")));
		assertThat(jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.credit),0) FROM journal_lines l JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
				JOIN purchase_bills b ON b.company_id=l.company_id AND b.journal_entry_id=l.journal_entry_id
				WHERE b.id=? AND a.system_key IN ('RCM_CGST','RCM_SGST')""", BigDecimal.class, rcmBillId)).isEqualByComparingTo("1800");
		post(c.url() + "/purchase-bills/" + rcmBillId + "/debit-notes", Map.of("vendorNoteNumber", "CN1", "noteDate", today.toString(),
				"reason", "x", "lines", List.of(Map.of("description", "x", "hsnSac", "998212", "quantity", 1, "unitPrice", 100, "gstRate", 18))),
				c.owner(), HttpStatus.BAD_REQUEST);

		long tdsBillId = number(post(c.url() + "/purchase-bills", Map.of("vendorId", consultant, "vendorInvoiceNumber", "C-1",
				"billDate", today.toString(), "tdsSection", "194J", "tdsRate", 10,
				"lines", List.of(Map.of("description", "Consulting", "hsnSac", "998311", "quantity", 1, "unitPrice", 10000, "gstRate", 18))),
				c.owner(), HttpStatus.OK).get("id"));
		approveAndPost(c, number(post(c.url() + "/purchase-bills/" + tdsBillId + "/book", Map.of(), c.owner(), HttpStatus.OK).get("journalEntryId")));

		post(c.url() + "/sales-invoices", Map.of("customerId", buyer, "invoiceDate", today.toString(), "tcsSection", "206C1H", "tcsRate", 1,
				"lines", List.of(Map.of("description", "Scrap", "hsnSac", "7204", "quantity", 1, "unitPrice", 100000, "gstRate", 18))),
				c.owner(), HttpStatus.BAD_REQUEST);
		Map<String, Object> invoice = post(c.url() + "/sales-invoices", Map.of("customerId", buyer, "invoiceDate", today.toString(),
				"tcsSection", "1-SCRAP", "tcsRate", 1,
				"lines", List.of(Map.of("description", "Scrap", "hsnSac", "7204", "quantity", 1, "unitPrice", 100000, "gstRate", 18))),
				c.owner(), HttpStatus.OK);
		assertThat(new BigDecimal(invoice.get("tcsAmount").toString())).isEqualByComparingTo("1180");
		assertThat(new BigDecimal(invoice.get("totalAmount").toString())).isEqualByComparingTo("119180");
		long invoiceId = number(invoice.get("id"));
		approveAndPost(c, number(post(c.url() + "/sales-invoices/" + invoiceId + "/issue", Map.of(), c.owner(), HttpStatus.OK).get("journalEntryId")));
		assertThat(jdbc.queryForObject("""
				SELECT COALESCE(SUM(l.credit),0) FROM journal_lines l JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id
				JOIN sales_invoices i ON i.company_id=l.company_id AND i.journal_entry_id=l.journal_entry_id
				WHERE i.id=? AND a.system_key='TCS_PAYABLE'""", BigDecimal.class, invoiceId)).isEqualByComparingTo("1180");

		String period = String.format("%02d%d", today.getMonthValue(), today.getYear());
		Map<String, Object> g3b = get(c.url() + "/gst/gstr3b?period=" + period, c.owner());
		Map<?, ?> t61 = (Map<?, ?>) g3b.get("table6_1");
		// Liability CGST/SGST 9000 each; ITC IGST 1800 + RCM CGST/SGST 900 each; RCM 1800 is paid in cash.
		assertThat(new BigDecimal(((Map<?, ?>) t61.get("cashForReverseCharge")).get("total").toString())).isEqualByComparingTo("1800");
		assertThat(new BigDecimal(((Map<?, ?>) t61.get("cashForForwardCharge")).get("total").toString())).isEqualByComparingTo("14400");
		assertThat(new BigDecimal(((Map<?, ?>) t61.get("totalCash")).get("total").toString())).isEqualByComparingTo("16200");
		assertThat((List<?>) g3b.get("warnings")).anyMatch(w -> w.toString().contains("has not been imported"));

		post(c.url() + "/tds/challans", Map.of("bsrCode", "0510002", "challanSerial", "12345", "depositDate", today.toString(),
				"taxAmount", 1000, "bankAccountId", accountId(c, "1010"), "allocations", List.of(Map.of("billId", tdsBillId, "amount", 1001))),
				c.owner(), HttpStatus.BAD_REQUEST);
		Map<String, Object> challan = post(c.url() + "/tds/challans", Map.of("bsrCode", "0510002", "challanSerial", "12345",
				"depositDate", today.toString(), "taxAmount", 1000, "bankAccountId", accountId(c, "1010"),
				"allocations", List.of(Map.of("billId", tdsBillId, "amount", 1000))), c.owner(), HttpStatus.OK);
		assertThat(new BigDecimal(challan.get("allocated").toString())).isEqualByComparingTo("1000");
		approveAndPost(c, number(challan.get("journalEntryId")));

		Map<String, Object> q = get(c.url() + "/tds/26q?fy=" + IndiaTax.fiscalYear(today) + "&quarter=" + TdsRules.quarter(today), c.owner());
		List<Map<String, Object>> deductees = (List<Map<String, Object>>) q.get("deductees");
		assertThat(deductees).singleElement().satisfies(d -> {
			assertThat(new BigDecimal(d.get("unallocated").toString())).isEqualByComparingTo("0");
			assertThat(d.get("panMissing")).isEqualTo(false);
		});
		assertThat((List<?>) q.get("problems")).anyMatch(p -> p.toString().contains("TAN is missing"));
		c.owner().put(c.url() + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27", "tan", "MUMA12345B"));
		assertThat(get(c.url() + "/india/profile", c.owner()).get("tan")).isEqualTo("MUMA12345B");
		c.owner().put(c.url() + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27"));
		assertThat(get(c.url() + "/india/profile", c.owner()).get("tan")).isEqualTo("MUMA12345B");
	}
}
