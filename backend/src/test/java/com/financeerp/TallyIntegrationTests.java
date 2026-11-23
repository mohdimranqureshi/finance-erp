package com.financeerp;

import com.financeerp.domain.TallyXmlParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TallyIntegrationTests extends IntegrationTestSupport {
	private static byte[] sampleUtf16() throws Exception {
		String xml = Files.readString(Path.of("..", "samples", "tally-daybook-sample.xml"), StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xFE);
		out.writeBytes(xml.getBytes(StandardCharsets.UTF_16LE));
		return out.toByteArray();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Map<String, Object> upload(String url, TestRestTemplate client, byte[] file, String mapping,
			HttpStatus expected) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(file) {
			@Override
			public String getFilename() {
				return "daybook.xml";
			}
		});
		if (mapping != null) {
			HttpHeaders json = new HttpHeaders();
			json.setContentType(MediaType.APPLICATION_JSON);
			body.add("mapping", new HttpEntity<>(mapping, json));
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		ResponseEntity<Map> response = client.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode()).as(url + " -> " + response.getBody()).isEqualTo(expected);
		return response.getBody();
	}

	@Test
	@SuppressWarnings("unchecked")
	void previewImportReimportAndExportRoundTrip() throws Exception {
		TestCompany c = indiaCompany("Tally Traders");
		byte[] file = sampleUtf16();

		Map<String, Object> preview = upload(c.url() + "/tally/import/preview", c.owner(), file, null, HttpStatus.OK);
		Map<String, Object> summary = (Map<String, Object>) preview.get("summary");
		assertThat(number(summary.get("importable"))).isEqualTo(4);
		assertThat(number(summary.get("unmapped"))).isEqualTo(1);
		List<Map<String, Object>> ledgers = (List<Map<String, Object>>) preview.get("ledgers");
		Map<String, Object> acme = ledgers.stream().filter(l -> "Acme Traders".equals(l.get("ledgerName"))).findFirst()
				.orElseThrow();
		assertThat(acme.get("action")).isEqualTo("NEW_PARTY");
		assertThat(acme.get("gstin")).isEqualTo("29AAPFU0939F1ZR");
		assertThat(number(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE company_id=?", Long.class,
				c.id()))).as("preview writes nothing").isZero();

		long otherExpenses = accountId(c, "5900");
		Map<String, Object> imported = upload(c.url() + "/tally/import", c.owner(), file,
				"{\"Suspense\": " + otherExpenses + "}", HttpStatus.OK);
		assertThat(number(imported.get("journalsCreated"))).isEqualTo(5);
		assertThat(number(imported.get("failed"))).isEqualTo(1);
		assertThat(number(imported.get("skipped"))).isEqualTo(4);
		assertThat(number(imported.get("partiesCreated"))).isEqualTo(2);
		assertThat(number(imported.get("accountsCreated"))).as("India chart has 'Rent', not 'Office Rent'").isEqualTo(9);
		assertThat(jdbc.queryForList("SELECT DISTINCT status FROM journal_entries WHERE company_id=?", String.class,
				c.id())).containsExactly("DRAFT");
		assertThat(jdbc.queryForObject("SELECT gstin FROM parties WHERE company_id=? AND name='Acme Traders'",
				String.class, c.id())).isEqualTo("29AAPFU0939F1ZR");
		assertThat(jdbc.queryForObject("SELECT gstin FROM parties WHERE company_id=? AND name='Bharat Supplies'",
				String.class, c.id())).as("invalid GSTIN dropped").isNull();
		long saleId = jdbc.queryForObject("SELECT id FROM journal_entries WHERE company_id=? AND idempotency_key=?",
				Long.class, c.id(), "TALLY:4f9c2a10-1d2e-4b3c-9f00-6a7b8c9d0e1f-00000001");
		Map<String, Object> arLine = jdbc.queryForMap(
				"SELECT l.debit,l.description FROM journal_lines l JOIN accounts a ON a.company_id=l.company_id AND a.id=l.account_id "
						+ "WHERE l.company_id=? AND l.journal_entry_id=? AND a.system_key='AR'",
				c.id(), saleId);
		assertThat((java.math.BigDecimal) arLine.get("debit")).isEqualByComparingTo("11800");
		assertThat(arLine.get("description")).isEqualTo("Acme Traders");
		assertThat(number(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE company_id=? AND action='TALLY_IMPORTED'", Long.class, c.id())))
				.isEqualTo(1);

		Map<String, Object> again = upload(c.url() + "/tally/import", c.owner(), file,
				"{\"Suspense\": " + otherExpenses + "}", HttpStatus.OK);
		assertThat(number(again.get("journalsCreated"))).isZero();
		assertThat(number(again.get("accountsCreated"))).isZero();
		assertThat(number(again.get("partiesCreated"))).isZero();
		assertThat(number(again.get("skipped"))).isEqualTo(9);

		upload(c.url() + "/tally/import", c.approver(), file, null, HttpStatus.FORBIDDEN);
		String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ENVELOPE>&e;</ENVELOPE>";
		upload(c.url() + "/tally/import/preview", c.owner(), xxe.getBytes(StandardCharsets.UTF_8), null,
				HttpStatus.BAD_REQUEST);
		upload(c.url() + "/tally/import", c.owner(), file, "{\"Cash\": \"BANANA\"}", HttpStatus.BAD_REQUEST);

		post(c.url() + "/periods", Map.of("name", "FY2024-25", "startDate", "2024-04-01", "endDate", "2025-03-31"),
				c.owner(), HttpStatus.OK);
		approveAndPost(c, saleId);
		ResponseEntity<String> export = c.approver()
				.getForEntity(c.url() + "/tally/export?from=2024-04-01&to=2024-04-30", String.class);
		assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(export.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML)).isTrue();
		String xml = export.getBody();
		assertThat(xml).contains("<TALLYREQUEST>Import Data</TALLYREQUEST>", "<PARENT>Sundry Debtors</PARENT>",
				"<VOUCHERNUMBER>" + saleId + "</VOUCHERNUMBER>", "<AMOUNT>-11800.00</AMOUNT>");
		TallyXmlParser.TallyData back = TallyXmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
		assertThat(back.vouchers()).as("only POSTED journals are exported").hasSize(1);
		assertThat(back.vouchers().get(0).total()).isEqualByComparingTo("0");
		assertThat(back.vouchers().get(0).narration()).isEqualTo("Tally Sales #S/001: Consulting & support for April");
		assertThat(back.ledgers()).extracting(TallyXmlParser.Ledger::name).contains("Trade Receivables", "Sales @18%",
				"IGST Output");

		ResponseEntity<Map> badRange = c.owner().getForEntity(c.url() + "/tally/export?from=2024-05-01&to=2024-04-01",
				Map.class);
		assertThat(badRange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		ResponseEntity<String> outsider = user("Outsider")
				.getForEntity(c.url() + "/tally/export?from=2024-04-01&to=2024-04-30", String.class);
		assertThat(outsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
