package com.financeerp;

import com.financeerp.india.IndiaTax;
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
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.financeerp.ai.LlmClient llm;

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

    @Test void gstInvoiceIssuesDraftJournalAndFlowsIntoGstSummaryAndStatements() {
        String base = "http://localhost:" + port;
        post(base + "/api/v1/auth/register", Map.of("email", "gst-owner@example.test", "password", "a-strong-gst-owner-pw", "displayName", "GST Owner"), null, HttpStatus.OK);
        post(base + "/api/v1/auth/register", Map.of("email", "gst-approver@example.test", "password", "a-strong-gst-approver-pw", "displayName", "GST Approver"), null, HttpStatus.OK);
        TestRestTemplate owner = rest.withBasicAuth("gst-owner@example.test", "a-strong-gst-owner-pw");
        TestRestTemplate approver = rest.withBasicAuth("gst-approver@example.test", "a-strong-gst-approver-pw");
        long companyId = number(post(base + "/api/v1/companies", Map.of("name", "GST Company", "baseCurrency", "INR"), owner, HttpStatus.OK).get("id"));
        String co = base + "/api/v1/companies/" + companyId;
        post(co + "/members", Map.of("email", "gst-approver@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        LocalDate today = LocalDate.now();

        owner.put(co + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27"));
        post(co + "/india/chart-of-accounts", Map.of(), owner, HttpStatus.OK);
        Map<String,Object> customer = post(co + "/parties", Map.of("partyType", "CUSTOMER", "name", "Bengaluru Customer",
            "gstin", "29AAPFU0939F1ZR", "stateCode", "29"), owner, HttpStatus.OK);
        post(co + "/parties", Map.of("partyType", "CUSTOMER", "name", "Bad GSTIN", "gstin", "29AAPFU0939F1ZX", "stateCode", "29"), owner, HttpStatus.BAD_REQUEST);

        Map<String,Object> invoice = post(co + "/sales-invoices", Map.of("customerId", number(customer.get("id")), "invoiceDate", today.toString(),
            "lines", List.of(Map.of("description", "Software subscription", "hsnSac", "998314", "quantity", 1, "unitPrice", 10000, "gstRate", 18))), owner, HttpStatus.OK);
        assertThat(invoice.get("supplyType")).isEqualTo("INTER_STATE");
        assertThat(new java.math.BigDecimal(invoice.get("igstAmount").toString())).isEqualByComparingTo("1800");
        assertThat(new java.math.BigDecimal(invoice.get("totalAmount").toString())).isEqualByComparingTo("11800");
        long invoiceId = number(invoice.get("id"));
        Map<String,Object> issued = post(co + "/sales-invoices/" + invoiceId + "/issue", Map.of(), owner, HttpStatus.OK);
        assertThat(issued.get("invoiceNumber")).isEqualTo("INV/" + IndiaTax.fiscalYear(today).substring(2) + "/00001");
        assertThat(issued.get("journalStatus")).isEqualTo("DRAFT");
        post(co + "/sales-invoices/" + invoiceId + "/issue", Map.of(), owner, HttpStatus.BAD_REQUEST);

        long journalId = number(issued.get("journalEntryId"));
        post(co + "/journals/" + journalId + "/submit", Map.of(), owner, HttpStatus.OK);
        post(co + "/journals/" + journalId + "/approve", Map.of(), owner, HttpStatus.FORBIDDEN);
        post(co + "/journals/" + journalId + "/approve", Map.of(), approver, HttpStatus.OK);
        post(co + "/journals/" + journalId + "/post", Map.of(), owner, HttpStatus.OK);

        String range = "from=" + today.withDayOfYear(1) + "&to=" + today;
        Map<?,?> gst = owner.getForEntity(co + "/reports/gst-summary?" + range, Map.class).getBody();
        assertThat(new java.math.BigDecimal(((Map<?,?>) gst.get("outwardSupplies")).get("igst").toString())).isEqualByComparingTo("1800");
        Map<?,?> pnl = owner.getForEntity(co + "/reports/profit-and-loss?" + range, Map.class).getBody();
        assertThat(new java.math.BigDecimal(pnl.get("netProfit").toString())).isEqualByComparingTo("10000");
        Map<?,?> sheet = owner.getForEntity(co + "/reports/balance-sheet?asOf=" + today, Map.class).getBody();
        assertThat(sheet.get("balanced")).isEqualTo(true);
    }

    @Test void settlementsCreditNotesBankReconciliationAndYearEndCloseWorkTogether() {
        String base = "http://localhost:" + port;
        post(base + "/api/v1/auth/register", Map.of("email", "ops-owner@example.test", "password", "a-strong-ops-owner-pw", "displayName", "Ops Owner"), null, HttpStatus.OK);
        post(base + "/api/v1/auth/register", Map.of("email", "ops-approver@example.test", "password", "a-strong-ops-approver-pw", "displayName", "Ops Approver"), null, HttpStatus.OK);
        TestRestTemplate owner = rest.withBasicAuth("ops-owner@example.test", "a-strong-ops-owner-pw");
        TestRestTemplate approver = rest.withBasicAuth("ops-approver@example.test", "a-strong-ops-approver-pw");
        long companyId = number(post(base + "/api/v1/companies", Map.of("name", "Ops Company", "baseCurrency", "INR"), owner, HttpStatus.OK).get("id"));
        String co = base + "/api/v1/companies/" + companyId;
        post(co + "/members", Map.of("email", "ops-approver@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        LocalDate today = LocalDate.now();
        owner.put(co + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27", "addressLine", "1 Marine Drive", "city", "Mumbai", "pincode", "400001"));
        post(co + "/india/chart-of-accounts", Map.of(), owner, HttpStatus.OK);
        Map<String,Object> customer = post(co + "/parties", Map.of("partyType", "CUSTOMER", "name", "Bengaluru Customer",
            "gstin", "29AAPFU0939F1ZR", "stateCode", "29", "addressLine", "5 MG Road", "city", "Bengaluru", "pincode", "560001"), owner, HttpStatus.OK);
        Map<String,Object> invoice = post(co + "/sales-invoices", Map.of("customerId", number(customer.get("id")), "invoiceDate", today.toString(),
            "lines", List.of(Map.of("description", "Goods", "hsnSac", "8471", "quantity", 1, "unitPrice", 10000, "gstRate", 18))), owner, HttpStatus.OK);
        long invoiceId = number(invoice.get("id"));
        Map<String,Object> issued = post(co + "/sales-invoices/" + invoiceId + "/issue", Map.of(), owner, HttpStatus.OK);
        approveAndPost(co, number(issued.get("journalEntryId")), owner, approver);

        Map<String,Object> eInvoice = owner.getForEntity(co + "/sales-invoices/" + invoiceId + "/e-invoice-payload", Map.class).getBody();
        assertThat(eInvoice.get("Version")).isEqualTo("1.1");
        Map<String,Object> eWay = post(co + "/sales-invoices/" + invoiceId + "/e-way-bill-payload",
            Map.of("transMode", 1, "transDistance", 900, "vehicleNo", "MH12AB1234", "vehicleType", "R"), owner, HttpStatus.OK);
        assertThat(eWay.get("required")).isEqualTo(false);

        Map<String,Object> note = post(co + "/sales-invoices/" + invoiceId + "/credit-notes", Map.of("noteDate", today.toString(), "reason", "Returned goods",
            "lines", List.of(Map.of("description", "Goods returned", "hsnSac", "8471", "quantity", 1, "unitPrice", 1000, "gstRate", 18))), owner, HttpStatus.OK);
        assertThat(new java.math.BigDecimal(note.get("totalAmount").toString())).isEqualByComparingTo("1180");
        approveAndPost(co, number(note.get("journalEntryId")), owner, approver);

        post(co + "/sales-invoices/" + invoiceId + "/receipts", Map.of("paymentDate", today.toString(), "amount", 10700), owner, HttpStatus.BAD_REQUEST);
        Map<String,Object> receipt = post(co + "/sales-invoices/" + invoiceId + "/receipts",
            Map.of("paymentDate", today.toString(), "amount", 10520, "tdsAmount", 100, "reference", "NEFT-1"), owner, HttpStatus.OK);
        approveAndPost(co, number(receipt.get("journalEntryId")), owner, approver);
        Map<?,?> open = owner.getForEntity(co + "/open-items", Map.class).getBody();
        assertThat((List<?>) open.get("receivables")).isEmpty();

        List<?> accounts = owner.getForEntity(co + "/accounts", List.class).getBody();
        long bankId = accounts.stream().map(a -> (Map<?,?>) a).filter(a -> "1010".equals(a.get("code"))).map(a -> number(a.get("id"))).findFirst().orElseThrow();
        post(co + "/bank/statement-lines", Map.of("bankAccountId", bankId, "lines", List.of(
            Map.of("date", today.toString(), "description", "NEFT from customer", "amount", 10520, "externalId", "stmt-1"))), owner, HttpStatus.OK);
        List<Map<String,Object>> lines = owner.getForEntity(co + "/bank/statement-lines?bankAccountId=" + bankId + "&unmatchedOnly=true", List.class).getBody();
        assertThat(lines).hasSize(1);
        List<Map<String,Object>> suggestions = (List<Map<String,Object>>) lines.get(0).get("suggestions");
        assertThat(suggestions).hasSize(1);
        post(co + "/bank/statement-lines/" + number(lines.get(0).get("id")) + "/match", Map.of("paymentId", number(suggestions.get(0).get("paymentId"))), owner, HttpStatus.OK);
        Map<?,?> recon = owner.getForEntity(co + "/bank/reconciliation?bankAccountId=" + bankId + "&asOf=" + today, Map.class).getBody();
        assertThat(new java.math.BigDecimal(recon.get("difference").toString())).isEqualByComparingTo("0");

        Map<?,?> gstr1 = owner.getForEntity(co + "/gst/gstr1?from=" + today.withDayOfYear(1) + "&to=" + today, Map.class).getBody();
        assertThat((List<?>) gstr1.get("b2b")).hasSize(1);
        assertThat((List<?>) gstr1.get("cdnr")).hasSize(1);

        List<Map<String,Object>> periods = owner.getForEntity(co + "/periods", List.class).getBody();
        long reservesId = accounts.stream().map(a -> (Map<?,?>) a).filter(a -> "3100".equals(a.get("code"))).map(a -> number(a.get("id"))).findFirst().orElseThrow();
        Map<String,Object> closing = post(co + "/year-end/closing-journal", Map.of("periodId", number(periods.get(0).get("id")), "reservesAccountId", reservesId), owner, HttpStatus.OK);
        approveAndPost(co, number(closing.get("journalId")), owner, approver);
        Map<?,?> pnl = owner.getForEntity(co + "/reports/profit-and-loss?from=" + today.withDayOfYear(1) + "&to=" + today, Map.class).getBody();
        assertThat(new java.math.BigDecimal(pnl.get("netProfit").toString())).isEqualByComparingTo("9000");
        Map<?,?> sheet = owner.getForEntity(co + "/reports/balance-sheet?asOf=" + today, Map.class).getBody();
        assertThat(sheet.get("balanced")).isEqualTo(true);
    }

    @Test void revenueScheduleDefersThenRecognisesThroughApprovedJournals() {
        String base = "http://localhost:" + port;
        post(base + "/api/v1/auth/register", Map.of("email", "rr-owner@example.test", "password", "a-strong-rr-owner-pw", "displayName", "RR Owner"), null, HttpStatus.OK);
        post(base + "/api/v1/auth/register", Map.of("email", "rr-approver@example.test", "password", "a-strong-rr-approver-pw", "displayName", "RR Approver"), null, HttpStatus.OK);
        TestRestTemplate owner = rest.withBasicAuth("rr-owner@example.test", "a-strong-rr-owner-pw");
        TestRestTemplate approver = rest.withBasicAuth("rr-approver@example.test", "a-strong-rr-approver-pw");
        long companyId = number(post(base + "/api/v1/companies", Map.of("name", "RR Company", "baseCurrency", "INR"), owner, HttpStatus.OK).get("id"));
        String co = base + "/api/v1/companies/" + companyId;
        post(co + "/members", Map.of("email", "rr-approver@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        LocalDate today = LocalDate.now();
        owner.put(co + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27"));
        post(co + "/india/chart-of-accounts", Map.of(), owner, HttpStatus.OK);
        Map<String,Object> customer = post(co + "/parties", Map.of("partyType", "CUSTOMER", "name", "Subscriber", "stateCode", "27"), owner, HttpStatus.OK);
        Map<String,Object> invoice = post(co + "/sales-invoices", Map.of("customerId", number(customer.get("id")), "invoiceDate", today.toString(),
            "lines", List.of(Map.of("description", "Annual licence", "hsnSac", "998314", "quantity", 1, "unitPrice", 12000, "gstRate", 18))), owner, HttpStatus.OK);
        long invoiceId = number(invoice.get("id"));
        approveAndPost(co, number(post(co + "/sales-invoices/" + invoiceId + "/issue", Map.of(), owner, HttpStatus.OK).get("journalEntryId")), owner, approver);

        post(co + "/sales-invoices/" + invoiceId + "/revenue-schedule", Map.of("startDate", today.toString(), "months", 1, "amount", 13000), owner, HttpStatus.BAD_REQUEST);
        Map<String,Object> schedule = post(co + "/sales-invoices/" + invoiceId + "/revenue-schedule", Map.of("startDate", today.toString(), "months", 1), owner, HttpStatus.OK);
        long scheduleId = number(schedule.get("id"));
        Map<String,Object> early = post(co + "/revenue-schedules/recognize", Map.of("asOf", today.plusMonths(1).withDayOfMonth(1).toString()), owner, HttpStatus.OK);
        assertThat(number(early.get("draftJournalsCreated"))).isZero();

        List<Map<String,Object>> before = owner.getForEntity(co + "/journals", List.class).getBody();
        long deferralJournalId = before.stream().filter(j -> String.valueOf(j.get("description")).startsWith("Defer revenue")).map(j -> number(j.get("id"))).findFirst().orElseThrow();
        approveAndPost(co, deferralJournalId, owner, approver);

        Map<String,Object> recognised = post(co + "/revenue-schedules/recognize", Map.of("asOf", today.plusMonths(1).withDayOfMonth(1).toString()), owner, HttpStatus.OK);
        assertThat(number(recognised.get("draftJournalsCreated"))).isEqualTo(1L);
        Map<?,?> detail = owner.getForEntity(co + "/revenue-schedules/" + scheduleId, Map.class).getBody();
        long recognitionJournalId = number(((Map<?,?>) ((List<?>) detail.get("entries")).get(0)).get("journalEntryId"));
        approveAndPost(co, recognitionJournalId, owner, approver);

        Map<?,?> after = owner.getForEntity(co + "/revenue-schedules/" + scheduleId, Map.class).getBody();
        assertThat(new java.math.BigDecimal(after.get("remaining").toString())).isEqualByComparingTo("0");
        Map<?,?> sheet = owner.getForEntity(co + "/reports/balance-sheet?asOf=" + today, Map.class).getBody();
        assertThat(sheet.get("balanced")).isEqualTo(true);
    }

    @Test void aiProposalsAreValidatedAndOnlyBecomeDraftsAfterAPersonAccepts() throws Exception {
        String base = "http://localhost:" + port;
        org.mockito.Mockito.when(llm.available()).thenReturn(true);
        org.mockito.Mockito.when(llm.model()).thenReturn("test-model");
        org.mockito.Mockito.when(llm.maxInputChars()).thenReturn(20000);
        post(base + "/api/v1/auth/register", Map.of("email", "ai-owner@example.test", "password", "a-strong-ai-owner-pw", "displayName", "AI Owner"), null, HttpStatus.OK);
        post(base + "/api/v1/auth/register", Map.of("email", "ai-approver@example.test", "password", "a-strong-ai-approver-pw", "displayName", "AI Approver"), null, HttpStatus.OK);
        TestRestTemplate owner = rest.withBasicAuth("ai-owner@example.test", "a-strong-ai-owner-pw");
        TestRestTemplate approver = rest.withBasicAuth("ai-approver@example.test", "a-strong-ai-approver-pw");
        long companyId = number(post(base + "/api/v1/companies", Map.of("name", "AI Company", "baseCurrency", "INR"), owner, HttpStatus.OK).get("id"));
        String co = base + "/api/v1/companies/" + companyId;
        post(co + "/members", Map.of("email", "ai-approver@example.test", "role", "APPROVER"), owner, HttpStatus.OK);
        LocalDate today = LocalDate.now();
        owner.put(co + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27"));
        post(co + "/india/chart-of-accounts", Map.of(), owner, HttpStatus.OK);
        Map<String,Object> vendor = post(co + "/parties", Map.of("partyType", "VENDOR", "name", "Law Associates", "gstin", "29AAPFU0939F1ZR", "stateCode", "29"), owner, HttpStatus.OK);

        String billJson = "{\"vendorName\":\"Law Associates\",\"vendorGstin\":\"29AAPFU0939F1ZR\",\"invoiceNumber\":\"V-001\",\"invoiceDate\":\"" + today
            + "\",\"documentTotal\":11800,\"tdsSection\":\"194J\",\"confidence\":0.93,\"lines\":[{\"description\":\"Legal fees\",\"hsnSac\":\"998211\",\"quantity\":1,\"unitPrice\":10000,\"gstRate\":18,\"expenseAccountCode\":\"5300\"}]}";
        org.mockito.Mockito.when(llm.completeJson(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(billJson));
        Map<String,Object> proposal = post(co + "/ai/extract-bill", Map.of("text", "Tax invoice V-001 Law Associates legal fees 10000 + GST"), owner, HttpStatus.OK);
        assertThat((List<?>) proposal.get("blockers")).isEmpty();
        assertThat(number(((Map<?,?>) proposal.get("payload")).get("vendorId"))).isEqualTo(number(vendor.get("id")));
        assertThat(owner.getForEntity(co + "/purchase-bills", List.class).getBody()).isEmpty();

        Map<String,Object> accepted = post(co + "/ai/proposals/" + number(proposal.get("id")) + "/accept", Map.of(), owner, HttpStatus.OK);
        assertThat(accepted.get("status")).isEqualTo("ACCEPTED");
        List<Map<String,Object>> bills = owner.getForEntity(co + "/purchase-bills", List.class).getBody();
        assertThat(bills).hasSize(1);
        assertThat(bills.get(0).get("status")).isEqualTo("DRAFT");
        assertThat(new java.math.BigDecimal(bills.get(0).get("tdsAmount").toString())).isEqualByComparingTo("1000");
        post(co + "/ai/proposals/" + number(proposal.get("id")) + "/accept", Map.of(), owner, HttpStatus.BAD_REQUEST);

        String badJournal = "{\"description\":\"Unbalanced\",\"lines\":[{\"accountCode\":\"1000\",\"debit\":500,\"credit\":0},{\"accountCode\":\"3000\",\"debit\":0,\"credit\":400}]}";
        org.mockito.Mockito.when(llm.completeJson(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(badJournal));
        post(co + "/ai/draft-journal", Map.of("instruction", "Owner put in 500"), owner, HttpStatus.BAD_REQUEST);

        String goodJournal = "{\"description\":\"Owner capital\",\"confidence\":0.9,\"lines\":[{\"accountCode\":\"1000\",\"debit\":500,\"credit\":0},{\"accountCode\":\"3000\",\"debit\":0,\"credit\":500}]}";
        org.mockito.Mockito.when(llm.completeJson(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree(goodJournal));
        Map<String,Object> journalProposal = post(co + "/ai/draft-journal", Map.of("instruction", "Owner put in 500"), owner, HttpStatus.OK);
        Map<String,Object> journalAccepted = post(co + "/ai/proposals/" + number(journalProposal.get("id")) + "/accept", Map.of(), owner, HttpStatus.OK);
        long journalId = number(journalAccepted.get("resultId"));
        post(co + "/journals/" + journalId + "/submit", Map.of(), owner, HttpStatus.OK);
        post(co + "/journals/" + journalId + "/approve", Map.of(), owner, HttpStatus.FORBIDDEN);
        post(co + "/journals/" + journalId + "/approve", Map.of(), approver, HttpStatus.OK);

        Map<?,?> review = owner.getForEntity(co + "/ai/review", Map.class).getBody();
        assertThat(review.get("findings")).isNotNull();
    }

    private void approveAndPost(String co, long journalId, TestRestTemplate maker, TestRestTemplate checker) {
        post(co + "/journals/" + journalId + "/submit", Map.of(), maker, HttpStatus.OK);
        post(co + "/journals/" + journalId + "/approve", Map.of(), checker, HttpStatus.OK);
        post(co + "/journals/" + journalId + "/post", Map.of(), maker, HttpStatus.OK);
    }

    private Map<String,Object> post(String url, Object body, TestRestTemplate client, HttpStatus expected) {
        TestRestTemplate actual = client == null ? rest : client;
        ResponseEntity<Map> response = actual.postForEntity(url, body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody() == null ? Map.of() : response.getBody();
    }
    private long number(Object value) { return ((Number)value).longValue(); }
}
