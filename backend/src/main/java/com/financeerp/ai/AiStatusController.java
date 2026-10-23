package com.financeerp.ai;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiStatusController {
	private final LlmClient llm;

	public AiStatusController(LlmClient llm) {
		this.llm = llm;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		boolean on = llm.available();
		return Map.of("enabled", on, "mode", on ? "LLM_PROPOSALS" : "RULE_BASED_ASSIST", "llmProviderConfigured", on,
				"model", on ? llm.model() : "", "message",
				on ? "AI proposes; people approve. Extracted bills and drafted journals become DRAFT documents that still need independent approval. Document text and company figures are sent to the configured provider."
						: "No LLM is configured; only keyword-rule suggestions and the automated close review are available.");
	}
}
