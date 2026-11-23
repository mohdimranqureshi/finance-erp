package com.financeerp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AiStatusService {
	private final LlmClient llm;
	private final boolean visionEnabled;

	public AiStatusService(LlmClient llm,
			@Value("${app.ai.vision-enabled:${AI_VISION_ENABLED:false}}") boolean visionEnabled) {
		this.llm = llm;
		this.visionEnabled = visionEnabled;
	}

	public Map<String, Object> status() {
		boolean on = llm.available();
		return Map.of("enabled", on, "mode", on ? "LLM_PROPOSALS" : "RULE_BASED_ASSIST", "llmProviderConfigured", on,
				"model", on ? llm.model() : "", "visionEnabled", on && visionEnabled, "message",
				on ? "AI proposes; people approve. Extracted bills and drafted journals become DRAFT documents that still need independent approval. Document text and company figures are sent to the configured provider."
						+ (visionEnabled ? " Uploaded images and scanned PDF pages are sent to it as re-encoded PNG images." : "")
						: "No LLM is configured; only keyword-rule suggestions and the automated close review are available.");
	}
}
