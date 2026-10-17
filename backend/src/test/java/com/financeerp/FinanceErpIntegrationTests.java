package com.financeerp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinanceErpIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("finance_erp_test").withUsername("finance_test").withPassword("finance_test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test void companyAccessPeriodControlAndJournalReversalWorkTogether() {
        String base = "http://localhost:" + port;
        Map<String,Object> registered = post(base + "/api/v1/auth/register", Map.of(
            "email", "owner@example.test", "password", "a-strong-test-password", "displayName", "Test Owner"), null, HttpStatus.OK);
        assertThat(registered).containsKey("id");
        TestRestTemplate owner = rest.withBasicAuth("owner@example.test", "a-strong-test-password");

        Map<String,Object> company = post(base + "/api/v1/companies", Map.of("name", "Test Company", "baseCurrency", "INR"), owner, HttpStatus.OK);
        long companyId = number(company.get("id"));
        Map<String,Object> cash = post(base + "/api/v1/companies/" + companyId + "/accounts", Map.of("code", "1000", "name", "Cash", "accountType", "ASSET"), owner, HttpStatus.OK);
        Map<String,Object> capital = post(base + "/api/v1/companies/" + companyId + "/accounts", Map.of("code", "3000", "name", "Capital", "accountType", "EQUITY"), owner, HttpStatus.OK);
        LocalDate today = LocalDate.now();
        Map<String,Object> journal = post(base + "/api/v1/companies/" + companyId + "/journals", Map.of(
            "journalDate", today.toString(), "description", "Opening capital", "lines", List.of(
                Map.of("accountId", number(cash.get("id")), "debit", 1000, "credit", 0),
                Map.of("accountId", number(capital.get("id")), "debit", 0, "credit", 1000))), owner, HttpStatus.OK);
        long journalId = number(journal.get("id"));
        post(base + "/api/v1/auth/register", Map.of(
            "email", "approver@example.test", "password", "a-strong-approver-password", "displayName", "Test Approver"), null, HttpStatus.OK);
        post(base + "/api/v1/companies/" + companyId + "/members", Map.of("email", "approver@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        TestRestTemplate approver = rest.withBasicAuth("approver@example.test", "a-strong-approver-password");
        post(base + "/api/v1/companies/" + companyId + "/journals/" + journalId + "/submit", Map.of(), owner, HttpStatus.OK);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + journalId + "/approve", Map.of(), owner, HttpStatus.FORBIDDEN);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + journalId + "/approve", Map.of(), approver, HttpStatus.OK);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + journalId + "/post", Map.of(), owner, HttpStatus.OK);
        Map<String,Object> reversal = post(base + "/api/v1/companies/" + companyId + "/journals/" + journalId + "/reverse",
            Map.of("reversalDate", today.toString()), owner, HttpStatus.OK);
        long reversalId = number(reversal.get("id"));
        assertThat(number(reversal.get("reversalOfJournalId"))).isEqualTo(journalId);
        assertThat(reversal.get("status")).isEqualTo("DRAFT");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_lines WHERE company_id=? AND journal_entry_id=?", companyId, journalId))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + reversalId + "/submit", Map.of(), owner, HttpStatus.OK);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + reversalId + "/approve", Map.of(), approver, HttpStatus.OK);
        post(base + "/api/v1/companies/" + companyId + "/journals/" + reversalId + "/post", Map.of(), owner, HttpStatus.OK);

        ResponseEntity<List> balance = owner.exchange(base + "/api/v1/companies/" + companyId + "/reports/trial-balance?from=" + today.withDayOfYear(1) + "&to=" + today,
            HttpMethod.GET, HttpEntity.EMPTY, List.class);
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String,Object>> rows = balance.getBody();
        assertThat(rows).isNotNull().hasSize(2);
        for (Map<String,Object> row : rows) {
            assertThat(new java.math.BigDecimal(row.get("debitTotal").toString())).isEqualByComparingTo("1000.0000");
            assertThat(new java.math.BigDecimal(row.get("creditTotal").toString())).isEqualByComparingTo("1000.0000");
        }

        post(base + "/api/v1/auth/register", Map.of(
            "email", "outsider@example.test", "password", "another-strong-password", "displayName", "Outsider"), null, HttpStatus.OK);
        TestRestTemplate outsider = rest.withBasicAuth("outsider@example.test", "another-strong-password");
        ResponseEntity<String> denied = outsider.getForEntity(base + "/api/v1/companies/" + companyId + "/accounts", String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }


    @Test void invitationTokenIsSingleUseAndCreatesCompanyMembership() {
        String base = "http://localhost:" + port;
        post(base + "/api/v1/auth/register", Map.of("email", "invite-owner@example.test", "password", "a-strong-owner-password", "displayName", "Invite Owner"), null, HttpStatus.OK);
        TestRestTemplate owner = rest.withBasicAuth("invite-owner@example.test", "a-strong-owner-password");
        Map<String,Object> company = post(base + "/api/v1/companies", Map.of("name", "Invite Company", "baseCurrency", "INR"), owner, HttpStatus.OK);
        long companyId = number(company.get("id"));
        Map<String,Object> invitation = post(base + "/api/v1/companies/" + companyId + "/invitations",
            Map.of("email", "invited-user@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        String token = invitation.get("invitationToken").toString();
        Map<String,Object> accepted = post(base + "/api/v1/auth/accept-invitation", Map.of("token", token,
            "password", "a-strong-invited-password", "displayName", "Invited Approver"), null, HttpStatus.OK);
        assertThat(accepted.get("created")).isEqualTo(true);
        TestRestTemplate invited = rest.withBasicAuth("invited-user@example.test", "a-strong-invited-password");
        ResponseEntity<List> companies = invited.getForEntity(base + "/api/v1/companies", List.class);
        assertThat(companies.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(companies.getBody()).isNotNull().hasSize(1);
        post(base + "/api/v1/auth/accept-invitation", Map.of("token", token,
            "password", "a-strong-other-password", "displayName", "Replay"), null, HttpStatus.BAD_REQUEST);
    }

    private Map<String,Object> post(String url, Object body, TestRestTemplate client, HttpStatus expected) {
        TestRestTemplate actual = client == null ? rest : client;
        ResponseEntity<Map> response = actual.postForEntity(url, body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody() == null ? Map.of() : response.getBody();
    }
    private long number(Object value) { return ((Number)value).longValue(); }
}
