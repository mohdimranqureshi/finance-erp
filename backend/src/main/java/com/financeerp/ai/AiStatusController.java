package com.financeerp.ai;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiStatusController {
	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("enabled", false, "mode", "FOUNDATION_ONLY", "message",
				"No AI provider is configured. AI must remain a proposal layer and cannot post entries directly.");
	}
}
