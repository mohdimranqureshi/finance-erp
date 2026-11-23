package com.financeerp.controller;

import com.financeerp.dto.AiInsightsDto;
import com.financeerp.service.AiInsightsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Read-only AI features: grounded Q&A over the company's own figures and a deterministic close review. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiInsightsController {
	private final AiInsightsService service;

	public AiInsightsController(AiInsightsService service) {
		this.service = service;
	}

	@PostMapping("/ask")
	public Map<String, Object> ask(@PathVariable long companyId, @Valid @RequestBody AiInsightsDto.AskRequest req) {
		return service.ask(companyId, req);
	}

	@GetMapping("/review")
	public Map<String, Object> review(@PathVariable long companyId, @RequestParam(defaultValue = "false") boolean narrative) {
		return service.review(companyId, narrative);
	}
}
