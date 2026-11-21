package com.financeerp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class OpeningBalanceIntegrationTests extends IntegrationTestSupport {

	private static LocalDate fyStart() {
		LocalDate t = today();
		return LocalDate.of(t.getMonthValue() >= 4 ? t.getYear() : t.getYear() - 1, 4, 1);
	}

	private static Map<String, Object> party(String key, long id, String number, LocalDate date, LocalDate due, Object amount) {
		Map<String, Object> m = new HashMap<>();
		m.put(key, id);
		m.put("documentNumber", number);
		m.put("documentDate", date.toString());
		m.put("dueDate", due == null ? null : due.toString());
		m.put("amount", amount);
		return m;
	}

	private static BigDecimal dec(Object v) {
		return new BigDecimal(String.valueOf(v));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
		return (List<Map<String, Object>>) m.get(key);
	}

	@Test
	@SuppressWarnings("unchecked")
	void openingBalancesCreateOneDraftJournalAndPartyItemsSettleLikeInvoices() throws Exception {
		TestCompany c = indiaCompany("Opening Co");
		long customer = customer(c, "Old Customer", "27AAPFU0939F1ZV", "27");
		long vendor = vendor(c, "Old Vendor", "29AAPFU0939F1ZR", "29");
		LocalDate asOf = fyStart();
		long bank = accountId(c, "1010"), capital = accountId(c, "3000"), ar = accountId(c, "1100"),
				ap = accountId(c, "2000"), equity = accountId(c, "3900");
		String url = c.url() + "/opening-balances";
		List<Map<String, Object>> gl = List.of(Map.of("accountId", bank, "debit", 100000, "credit", 0, "memo", "HDFC balance"),
				Map.of("accountId", capital, "debit", 0, "credit", 50000));
		List<Map<String, Object>> receivables = List.of(
				party("customerId", customer, "INV-OLD-1", asOf.minusDays(40), asOf.minusDays(10), 30000),
				party("customerId", customer, "INV-OLD-2", asOf.minusDays(5), null, 5000));
		List<Map<String, Object>> payables = List.of(party("vendorId", vendor, "BILL-OLD-1", asOf.minusDays(20), asOf.plusDays(10), 12000));

		assertThat(get(url, c.owner()).get("batch")).isNull();
		// Validation failures roll back and leave no batch behind.
		post(url, Map.of("asOfDate", asOf.toString(), "lines", List.of(Map.of("accountId", ar, "debit", 100, "credit", 0))), c.owner(),
				HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "lines", List.of(Map.of("accountId", ap, "debit", 0, "credit", 100))), c.owner(),
				HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "receivables", List.of(party("customerId", vendor, "X-1", asOf, null, 10))),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "payables", List.of(party("vendorId", customer, "X-1", asOf, null, 10))),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "receivables", List.of(party("customerId", customer, "X-1", asOf, null, 10),
				party("customerId", customer, "x-1", asOf, null, 20))), c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "receivables", List.of(party("customerId", customer, "X-1", asOf.plusDays(1), null, 10))),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "receivables", List.of(party("customerId", customer, "X-1", asOf, null, 0))),
				c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.minusYears(1).toString(), "lines", gl), c.owner(), HttpStatus.BAD_REQUEST);
		post(url, Map.of("asOfDate", asOf.toString(), "lines", gl), c.approver(), HttpStatus.FORBIDDEN);
		assertThat(get(url, c.owner()).get("batch")).isNull();

		Map<String, Object> created = post(url, Map.of("asOfDate", asOf.toString(), "lines", gl, "receivables", receivables,
				"payables", payables), c.owner(), HttpStatus.OK);
		Map<String, Object> batch = (Map<String, Object>) created.get("batch");
		long journalId = number(batch.get("journalEntryId"));
		assertThat(batch.get("journalStatus")).isEqualTo("DRAFT");
		assertThat(batch.get("status")).isEqualTo("ACTIVE");
		assertThat(dec(batch.get("receivableTotal"))).isEqualByComparingTo("35000");
		assertThat(dec(batch.get("payableTotal"))).isEqualByComparingTo("12000");
		assertThat(dec(batch.get("equityAdjustment"))).isEqualByComparingTo("73000");
		assertThat(list(created, "receivables")).hasSize(2).allSatisfy(r -> assertThat(r.get("party")).isEqualTo("Old Customer"));
		assertThat(list(created, "payables")).singleElement().satisfies(p -> assertThat(dec(p.get("outstanding"))).isEqualByComparingTo("12000"));

		List<Map<String, Object>> lines = list(get(c.url() + "/journals/" + journalId, c.owner()), "lines");
		assertThat(lines).hasSize(5);
		assertThat(lines).anySatisfy(l -> {
			assertThat(number(l.get("accountId"))).isEqualTo(ar);
			assertThat(dec(l.get("debit"))).isEqualByComparingTo("35000");
		});
		assertThat(lines).anySatisfy(l -> {
			assertThat(number(l.get("accountId"))).isEqualTo(ap);
			assertThat(dec(l.get("credit"))).isEqualByComparingTo("12000");
		});
		assertThat(lines).anySatisfy(l -> {
			assertThat(number(l.get("accountId"))).isEqualTo(equity);
			assertThat(dec(l.get("credit"))).isEqualByComparingTo("73000");
		});
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE company_id=? AND action='OPENING_BALANCES_CREATED'",
				Integer.class, c.id())).isEqualTo(1);

		// Only one batch while the journal stands.
		post(url, Map.of("asOfDate", asOf.toString(), "lines", gl), c.owner(), HttpStatus.BAD_REQUEST);
		approveAndPost(c, journalId);

		Map<String, Object> open = get(c.url() + "/open-items", c.owner());
		assertThat(dec(open.get("totalReceivable"))).isEqualByComparingTo("35000");
		assertThat(dec(open.get("totalPayable"))).isEqualByComparingTo("12000");
		assertThat(list(open, "receivables")).hasSize(2).allSatisfy(r -> {
			assertThat(r.get("documentType")).isEqualTo("OPENING_BALANCE");
			assertThat(r.get("id")).isNull();
			assertThat(r.get("openingItemId")).isNotNull();
		});
		Map<String, Object> old1 = list(open, "receivables").stream().filter(r -> "INV-OLD-1".equals(r.get("number"))).findFirst().orElseThrow();
		assertThat(old1.get("bucket")).isNotEqualTo("CURRENT");
		long item1 = number(old1.get("openingItemId"));
		long item2 = number(list(open, "receivables").stream().filter(r -> "INV-OLD-2".equals(r.get("number"))).findFirst().orElseThrow()
				.get("openingItemId"));
		long payItem = number(list(open, "payables").get(0).get("openingItemId"));

		String items = c.url() + "/opening-balances/items/";
		post(items + item2 + "/receipts", Map.of("paymentDate", asOf.toString(), "amount", 4500, "tdsAmount", 600), c.owner(),
				HttpStatus.BAD_REQUEST);
		post(items + payItem + "/receipts", Map.of("paymentDate", asOf.toString(), "amount", 100), c.owner(), HttpStatus.BAD_REQUEST);
		post(items + item1 + "/payments", Map.of("paymentDate", asOf.toString(), "amount", 100), c.owner(), HttpStatus.BAD_REQUEST);
		post(items + payItem + "/payments", Map.of("paymentDate", asOf.toString(), "amount", 100, "tdsAmount", 10), c.owner(),
				HttpStatus.BAD_REQUEST);
		post(items + payItem + "/payments", Map.of("paymentDate", asOf.toString(), "amount", 12000.01), c.owner(), HttpStatus.BAD_REQUEST);

		Map<String, Object> receipt = post(items + item1 + "/receipts",
				Map.of("paymentDate", asOf.toString(), "amount", 29000, "tdsAmount", 1000, "reference", "NEFT-OB"), c.owner(), HttpStatus.OK);
		assertThat(receipt.get("journalStatus")).isEqualTo("DRAFT");
		assertThat(number(receipt.get("openingItemId"))).isEqualTo(item1);
		assertThat(receipt.get("salesInvoiceId")).isNull();
		List<Map<String, Object>> receiptLines = list(get(c.url() + "/journals/" + number(receipt.get("journalEntryId")), c.owner()), "lines");
		assertThat(receiptLines).anySatisfy(l -> {
			assertThat(number(l.get("accountId"))).isEqualTo(accountId(c, "1230"));
			assertThat(dec(l.get("debit"))).isEqualByComparingTo("1000");
		});
		assertThat(receiptLines).anySatisfy(l -> {
			assertThat(number(l.get("accountId"))).isEqualTo(ar);
			assertThat(dec(l.get("credit"))).isEqualByComparingTo("30000");
		});
		approveAndPost(c, number(receipt.get("journalEntryId")));
		Map<String, Object> payment = post(items + payItem + "/payments", Map.of("paymentDate", asOf.toString(), "amount", 12000), c.owner(),
				HttpStatus.OK);
		assertThat(payment.get("direction")).isEqualTo("PAYMENT");
		approveAndPost(c, number(payment.get("journalEntryId")));
		post(items + item1 + "/receipts", Map.of("paymentDate", asOf.toString(), "amount", 1), c.owner(), HttpStatus.BAD_REQUEST);

		Map<String, Object> after = get(url, c.owner());
		assertThat(list(after, "receivables")).anySatisfy(r -> {
			assertThat(r.get("documentNumber")).isEqualTo("INV-OLD-1");
			assertThat(dec(r.get("outstanding"))).isEqualByComparingTo("0");
		});
		assertThat(dec(list(after, "payables").get(0).get("outstanding"))).isEqualByComparingTo("0");
		open = get(c.url() + "/open-items", c.owner());
		assertThat(list(open, "receivables")).singleElement().satisfies(r -> assertThat(r.get("number")).isEqualTo("INV-OLD-2"));
		assertThat(list(open, "payables")).isEmpty();
		assertThat(dec(open.get("totalReceivable"))).isEqualByComparingTo("5000");

		// AI features that read open items keep working; opening rows are not citable as invoices.
		get(c.url() + "/ai/review", c.owner());
		org.mockito.Mockito.when(llm.completeJson(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
						"{\"answer\":\"5000 is due.\",\"figuresUsed\":[{\"label\":\"INV-OLD-2\",\"value\":\"5000\",\"ref\":\"inv:null\"}]}"));
		org.mockito.Mockito.when(llm.model()).thenReturn("test-model");
		Map<String, Object> answer = post(c.url() + "/ai/ask", Map.of("question", "What is outstanding?"), c.owner(), HttpStatus.OK);
		assertThat(list(answer, "figuresUsed")).singleElement().satisfies(f -> assertThat(f.get("source")).isNull());

		// Replacing the batch requires the posted opening journal to be reversed and the reversal posted.
		Map<String, Object> reversal = post(c.url() + "/journals/" + journalId + "/reverse", Map.of("reversalDate", asOf.toString()),
				c.owner(), HttpStatus.OK);
		post(url, Map.of("asOfDate", asOf.toString(), "lines", gl), c.owner(), HttpStatus.BAD_REQUEST);
		approveAndPost(c, number(reversal.get("id")));
		assertThat(list(get(c.url() + "/open-items", c.owner()), "receivables")).isEmpty();
		post(items + item2 + "/receipts", Map.of("paymentDate", asOf.toString(), "amount", 100), c.owner(), HttpStatus.BAD_REQUEST);

		Map<String, Object> replaced = post(url, Map.of("asOfDate", asOf.toString(), "lines",
				List.of(Map.of("accountId", bank, "debit", 1000, "credit", 0))), c.owner(), HttpStatus.OK);
		Map<String, Object> newBatch = (Map<String, Object>) replaced.get("batch");
		assertThat(number(newBatch.get("id"))).isNotEqualTo(number(batch.get("id")));
		assertThat(dec(newBatch.get("equityAdjustment"))).isEqualByComparingTo("1000");
		assertThat(list(replaced, "receivables")).isEmpty();
		assertThat(jdbc.queryForObject("SELECT status FROM opening_balance_batches WHERE id=?", String.class, number(batch.get("id"))))
				.isEqualTo("SUPERSEDED");
	}
}
