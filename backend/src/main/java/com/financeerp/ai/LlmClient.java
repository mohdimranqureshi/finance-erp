package com.financeerp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal client for any OpenAI-compatible chat completions endpoint. Prompts and keys are never logged. */
@Component
public class LlmClient {
	private final ObjectMapper mapper;
	private final boolean enabled;
	private final String endpoint;
	private final String apiKey;
	private final String model;
	private final int timeoutSeconds;
	private final int maxInputChars;
	private final int callsPerMinute;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Map<Long, Deque<Instant>> recentCalls = new ConcurrentHashMap<>();

	public LlmClient(ObjectMapper mapper, @Value("${app.ai.enabled:false}") boolean enabled,
			@Value("${app.ai.base-url:https://api.openai.com/v1}") String baseUrl,
			@Value("${app.ai.api-key:}") String apiKey, @Value("${app.ai.model:gpt-4o-mini}") String model,
			@Value("${app.ai.timeout-seconds:45}") int timeoutSeconds,
			@Value("${app.ai.max-input-chars:20000}") int maxInputChars,
			@Value("${app.ai.calls-per-minute:20}") int callsPerMinute) {
		this.mapper = mapper;
		this.enabled = enabled;
		this.apiKey = apiKey;
		this.model = model;
		this.timeoutSeconds = timeoutSeconds;
		this.maxInputChars = maxInputChars;
		this.callsPerMinute = callsPerMinute;
		String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		URI uri = URI.create(trimmed);
		boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
		if (enabled && !"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
			throw new IllegalStateException("app.ai.base-url must use https (plain http is allowed only for localhost).");
		this.endpoint = trimmed + "/chat/completions";
	}

	public boolean available() {
		return enabled;
	}

	public String model() {
		return model;
	}

	public int maxInputChars() {
		return maxInputChars;
	}

	/** Sends a system and user prompt and returns the model's JSON object reply. */
	public JsonNode completeJson(long companyId, String system, String user) {
		if (!enabled)
			throw new AiUnavailableException(
					"AI is not enabled. Set app.ai.enabled=true and configure the provider (see README).");
		throttle(companyId);
		ObjectNode body = mapper.createObjectNode();
		body.put("model", model);
		body.put("temperature", 0);
		body.putObject("response_format").put("type", "json_object");
		var messages = body.putArray("messages");
		messages.addObject().put("role", "system").put("content", system);
		messages.addObject().put("role", "user").put("content", user);
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
					.timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
			if (!apiKey.isBlank())
				request.header("Authorization", "Bearer " + apiKey);
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2)
				throw new AiUnavailableException("The AI provider returned an error (HTTP " + response.statusCode() + ").");
			String content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content")
					.asText("");
			return mapper.readTree(stripFences(content));
		} catch (IOException e) {
			throw new IllegalArgumentException("The AI returned an unreadable response. Please try again.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiUnavailableException("The AI request was interrupted.");
		}
	}

	private void throttle(long companyId) {
		Deque<Instant> calls = recentCalls.computeIfAbsent(companyId, k -> new ArrayDeque<>());
		synchronized (calls) {
			Instant cutoff = Instant.now().minusSeconds(60);
			while (!calls.isEmpty() && calls.peekFirst().isBefore(cutoff))
				calls.pollFirst();
			if (calls.size() >= callsPerMinute)
				throw new AiUnavailableException("AI request limit reached for this company. Try again in a minute.");
			calls.addLast(Instant.now());
		}
	}

	private static String stripFences(String s) {
		String t = s.trim();
		if (t.startsWith("```")) {
			int nl = t.indexOf('\n');
			t = nl < 0 ? t : t.substring(nl + 1);
			if (t.endsWith("```"))
				t = t.substring(0, t.length() - 3);
		}
		return t.trim();
	}
}
