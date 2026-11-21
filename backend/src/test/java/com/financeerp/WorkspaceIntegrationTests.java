package com.financeerp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceIntegrationTests extends IntegrationTestSupport {

	private long submittedJournal(TestCompany c, TestRestTemplate maker, String description) {
		Map<String, Object> journal = post(c.url() + "/journals", Map.of("journalDate", today().toString(),
				"description", description, "lines",
				List.of(Map.of("accountId", accountId(c, "1000"), "description", "Dr", "debit", 1500, "credit", 0),
						Map.of("accountId", accountId(c, "1010"), "description", "Cr", "debit", 0, "credit", 1500))),
				maker, HttpStatus.OK);
		long id = number(journal.get("id"));
		post(c.url() + "/journals/" + id + "/submit", Map.of(), maker, HttpStatus.OK);
		return id;
	}

	private String status(TestCompany c, long journalId) {
		return jdbc.queryForObject("SELECT status FROM journal_entries WHERE company_id=? AND id=?", String.class,
				c.id(), journalId);
	}

	@Test
	@SuppressWarnings("unchecked")
	void caSeesAllClientsAndBulkApprovesWithPerItemMakerChecker() {
		TestCompany alpha = indiaCompany("Alpha Traders");
		TestCompany beta = indiaCompany("Beta Foods");
		TestCompany outsider = indiaCompany("Gamma Not A Client");
		TestRestTemplate ca = user("CA");
		addMember(alpha.url(), alpha.owner(), ca, "APPROVER");
		addMember(beta.url(), beta.owner(), ca, "APPROVER");
		String caEmail = String.valueOf(get(base() + "/api/v1/auth/me", ca).get("email"));
		long caId = jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)", Long.class, caEmail);

		long alphaJournal = submittedJournal(alpha, alpha.owner(), "Alpha cash deposit");
		long betaJournal = submittedJournal(beta, beta.owner(), "Beta cash deposit");
		// The CA once acted as Beta's accountant and submitted a journal, so maker-checker must stop them approving it.
		jdbc.update("UPDATE company_memberships SET role='ACCOUNTANT' WHERE company_id=? AND user_id=?", beta.id(), caId);
		long ownJournal = submittedJournal(beta, ca, "Journal the CA submitted");
		jdbc.update("UPDATE company_memberships SET role='APPROVER' WHERE company_id=? AND user_id=?", beta.id(), caId);
		long outsiderJournal = submittedJournal(outsider, outsider.owner(), "Not visible to the CA");

		List<Map<String, Object>> clients = getList(base() + "/api/v1/workspace/clients?sort=name", ca);
		assertThat(clients).extracting(c -> c.get("name")).containsExactly("Alpha Traders", "Beta Foods");
		Map<String, Object> alphaRow = clients.get(0), betaRow = clients.get(1);
		assertThat(alphaRow.get("role")).isEqualTo("APPROVER");
		assertThat(alphaRow.get("gstin")).isEqualTo("27AAPFU0939F1ZV");
		Map<String, Object> alphaMetrics = (Map<String, Object>) alphaRow.get("metrics");
		Map<String, Object> betaMetrics = (Map<String, Object>) betaRow.get("metrics");
		assertThat(number(alphaMetrics.get("submittedJournals"))).isEqualTo(1);
		assertThat(number(alphaMetrics.get("approvableByMe"))).isEqualTo(1);
		assertThat(number(betaMetrics.get("submittedJournals"))).isEqualTo(2);
		assertThat(number(betaMetrics.get("approvableByMe"))).isEqualTo(1);
		assertThat(alphaMetrics.get("gstr2b")).isNull();
		assertThat(alphaRow.get("closeStatus")).isEqualTo("ATTENTION");
		assertThat((List<Map<String, Object>>) alphaRow.get("closeReasons")).extracting(r -> r.get("code"))
				.contains("AWAITING_APPROVAL", "NO_GSTR2B");

		assertThat(getList(base() + "/api/v1/workspace/clients?search=beta", ca)).extracting(c -> c.get("name"))
				.containsExactly("Beta Foods");
		assertThat(getList(base() + "/api/v1/workspace/clients?status=BLOCKED", ca)).isEmpty();

		Map<String, Object> queue = get(base() + "/api/v1/workspace/approvals?limit=10", ca);
		assertThat(number(queue.get("total"))).isEqualTo(2);
		List<Map<String, Object>> items = (List<Map<String, Object>>) queue.get("items");
		assertThat(items).extracting(i -> number(i.get("journalId"))).containsExactlyInAnyOrder(alphaJournal,
				betaJournal);
		assertThat(items).allSatisfy(i -> {
			assertThat(((Number) i.get("totalDebit")).doubleValue()).isEqualTo(1500.0);
			assertThat(i.get("periodOpen")).isEqualTo(true);
		});
		ResponseEntity<Map> tooBig = ca.getForEntity(base() + "/api/v1/workspace/approvals?limit=500", Map.class);
		assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		Map<String, Object> bulk = post(base() + "/api/v1/workspace/approvals/bulk",
				Map.of("items", List.of(Map.of("companyId", alpha.id(), "journalId", alphaJournal),
						Map.of("companyId", beta.id(), "journalId", ownJournal),
						Map.of("companyId", beta.id(), "journalId", betaJournal),
						Map.of("companyId", outsider.id(), "journalId", outsiderJournal))),
				ca, HttpStatus.OK);
		assertThat(number(bulk.get("approved"))).isEqualTo(2);
		assertThat(number(bulk.get("failed"))).isEqualTo(2);
		List<Map<String, Object>> results = (List<Map<String, Object>>) bulk.get("results");
		assertThat(results).extracting(r -> r.get("ok")).containsExactly(true, false, true, false);
		assertThat(String.valueOf(results.get(1).get("error"))).contains("Maker-checker");
		assertThat(String.valueOf(results.get(3).get("error"))).contains("do not have access");

		assertThat(status(alpha, alphaJournal)).isEqualTo("APPROVED");
		assertThat(status(beta, betaJournal)).isEqualTo("APPROVED");
		assertThat(status(beta, ownJournal)).isEqualTo("SUBMITTED");
		assertThat(status(outsider, outsiderJournal)).isEqualTo("SUBMITTED");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE company_id=? AND action='JOURNAL_APPROVED' AND entity_id=? AND lower(actor)=lower(?)",
				Integer.class, alpha.id(), String.valueOf(alphaJournal), caEmail)).isEqualTo(1);
		assertThat(number(get(base() + "/api/v1/workspace/approvals", ca).get("total"))).isZero();

		ResponseEntity<Map> pinned = ca.exchange(base() + "/api/v1/workspace/clients/" + beta.id() + "/settings",
				HttpMethod.PUT, new HttpEntity<>(Map.of("pinned", true, "label", "Retainer")), Map.class);
		assertThat(pinned.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> afterPin = getList(base() + "/api/v1/workspace/clients?sort=name", ca);
		assertThat(afterPin.get(0).get("name")).isEqualTo("Beta Foods");
		assertThat(afterPin.get(0).get("label")).isEqualTo("Retainer");
		assertThat(number(((Map<String, Object>) afterPin.get(0).get("metrics")).get("approvedNotPosted")))
				.isEqualTo(1);
		ResponseEntity<Map> foreign = ca.exchange(
				base() + "/api/v1/workspace/clients/" + outsider.id() + "/settings", HttpMethod.PUT,
				new HttpEntity<>(Map.of("pinned", true)), Map.class);
		assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
