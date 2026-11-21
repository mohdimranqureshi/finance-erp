package com.financeerp.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Global (company_id NULL) security audit events for USER entities. Never pass passwords or codes in details. */
@Component
public class SecurityAudit {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public SecurityAudit(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public void record(String actor, String action, Object userId, Map<String, ?> details) {
		String body;
		try {
			body = json.writeValueAsString(details == null ? Map.of() : details);
		} catch (JsonProcessingException ex) {
			body = "{}";
		}
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,?,'USER',?,CAST(? AS jsonb))",
				truncate(actor == null || actor.isBlank() ? "ANONYMOUS" : actor, 180), action,
				userId == null ? null : truncate(String.valueOf(userId), 100), body);
	}

	static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
