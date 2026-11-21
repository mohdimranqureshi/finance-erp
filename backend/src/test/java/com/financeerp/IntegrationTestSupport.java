package com.financeerp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared PostgreSQL container, HTTP helpers and India-company setup for feature integration tests. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("finance_erp_test").withUsername("finance_test").withPassword("finance_test");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@LocalServerPort
	protected int port;
	@Autowired
	protected TestRestTemplate rest;
	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected PasswordEncoder encoder;
	@MockitoBean
	protected com.financeerp.ai.LlmClient llm;

	/** An Indian GST-registered company (Maharashtra) with the India chart seeded, an owner and an independent approver. */
	protected record TestCompany(long id, String url, TestRestTemplate owner, TestRestTemplate approver) {
	}

	protected String base() {
		return "http://localhost:" + port;
	}

	protected TestRestTemplate user(String role) {
		String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test";
		String password = "a-strong-" + role.toLowerCase() + "-password";
		post(base() + "/api/v1/auth/register", Map.of("email", email, "password", password, "displayName", role), null,
				HttpStatus.OK);
		return rest.withBasicAuth(email, password);
	}

	protected TestCompany indiaCompany(String name) {
		TestRestTemplate owner = user("Owner");
		TestRestTemplate approver = user("Approver");
		long id = number(post(base() + "/api/v1/companies", Map.of("name", name, "baseCurrency", "INR"), owner,
				HttpStatus.OK).get("id"));
		String co = base() + "/api/v1/companies/" + id;
		addMember(co, owner, approver, "APPROVER");
		owner.put(co + "/india/profile", Map.of("gstin", "27AAPFU0939F1ZV", "stateCode", "27"));
		post(co + "/india/chart-of-accounts", Map.of(), owner, HttpStatus.OK);
		return new TestCompany(id, co, owner, approver);
	}

	protected void addMember(String co, TestRestTemplate owner, TestRestTemplate member, String role) {
		Map<?, ?> me = member.getForEntity(base() + "/api/v1/auth/me", Map.class).getBody();
		post(co + "/members", Map.of("email", me.get("email"), "role", role), owner, HttpStatus.OK);
	}

	protected long vendor(TestCompany c, String name, String gstin, String state) {
		return number(post(c.url() + "/parties", Map.of("partyType", "VENDOR", "name", name, "gstin", gstin, "stateCode", state),
				c.owner(), HttpStatus.OK).get("id"));
	}

	protected long customer(TestCompany c, String name, String gstin, String state) {
		return number(post(c.url() + "/parties", Map.of("partyType", "CUSTOMER", "name", name, "gstin", gstin, "stateCode", state),
				c.owner(), HttpStatus.OK).get("id"));
	}

	protected long accountId(TestCompany c, String code) {
		return jdbc.queryForObject("SELECT id FROM accounts WHERE company_id=? AND code=?", Long.class, c.id(), code);
	}

	protected void approveAndPost(TestCompany c, long journalId) {
		post(c.url() + "/journals/" + journalId + "/submit", Map.of(), c.owner(), HttpStatus.OK);
		post(c.url() + "/journals/" + journalId + "/approve", Map.of(), c.approver(), HttpStatus.OK);
		post(c.url() + "/journals/" + journalId + "/post", Map.of(), c.owner(), HttpStatus.OK);
	}

	protected TestRestTemplate adminClient() {
		jdbc.update(
				"INSERT INTO app_users(email,password_hash,display_name,is_admin) VALUES ('admin@example.test',?,'Admin',TRUE) ON CONFLICT (email) DO NOTHING",
				encoder.encode("a-strong-admin-password"));
		return rest.withBasicAuth("admin@example.test", "a-strong-admin-password");
	}

	@SuppressWarnings("unchecked")
	protected Map<String, Object> post(String url, Object body, TestRestTemplate client, HttpStatus expected) {
		TestRestTemplate actual = client != null ? client : url.endsWith("/api/v1/auth/register") ? adminClient() : rest;
		ResponseEntity<Map> response = actual.postForEntity(url, body, Map.class);
		assertThat(response.getStatusCode()).as(url + " -> " + response.getBody()).isEqualTo(expected);
		return response.getBody() == null ? Map.of() : response.getBody();
	}

	@SuppressWarnings("unchecked")
	protected Map<String, Object> get(String url, TestRestTemplate client) {
		ResponseEntity<Map> response = client.getForEntity(url, Map.class);
		assertThat(response.getStatusCode()).as(url + " -> " + response.getBody()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	@SuppressWarnings("unchecked")
	protected List<Map<String, Object>> getList(String url, TestRestTemplate client) {
		ResponseEntity<List> response = client.getForEntity(url, List.class);
		assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	protected static long number(Object value) {
		return ((Number) value).longValue();
	}

	protected static LocalDate today() {
		return LocalDate.now();
	}
}
