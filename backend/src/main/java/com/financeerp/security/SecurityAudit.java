package com.financeerp.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeerp.repository.AuditRepository;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Global (company_id NULL) security audit events for USER entities. Never pass passwords or codes in details. */
@Component
public class SecurityAudit {
	private final AuditRepository repository;
	private final ObjectMapper json;

	public SecurityAudit(AuditRepository repository, ObjectMapper json) {
		this.repository = repository;
		this.json = json;
	}

	public void record(String actor, String action, Object userId, Map<String, ?> details) {
		String body;
		try {
			body = json.writeValueAsString(details == null ? Map.of() : details);
		} catch (JsonProcessingException ex) {
			body = "{}";
		}
		repository.insertUserEvent(truncate(actor == null || actor.isBlank() ? "ANONYMOUS" : actor, 180), action,
				userId == null ? null : truncate(String.valueOf(userId), 100), body);
	}

	static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}