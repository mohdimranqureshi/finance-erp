package com.financeerp.controller;

import com.financeerp.service.AiAnalysisService;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Read-only analytics. Every number is computed by SQL or the report services; the model, when enabled, only
 * phrases findings it is given and can never add figures.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiAnalysisController {
	private final AiAnalysisService service;

	public AiAnalysisController(AiAnalysisService service) {
		this.service = service;
	}

	@GetMapping("/anomalies")
	public Map<String, Object> anomalies(@PathVariable long companyId, @RequestParam(defaultValue = "false") boolean narrative) {
		return service.anomalies(companyId, narrative);
	}

	@GetMapping("/flux")
	public Map<String, Object> flux(@PathVariable long companyId, @RequestParam LocalDate from, @RequestParam LocalDate to,
			@RequestParam(required = false) LocalDate compareFrom, @RequestParam(required = false) LocalDate compareTo,
			@RequestParam(defaultValue = "5000") BigDecimal minAmount, @RequestParam(defaultValue = "10") BigDecimal minPercent,
			@RequestParam(defaultValue = "false") boolean narrative) {
		return service.flux(companyId, from, to, compareFrom, compareTo, minAmount, minPercent, narrative);
	}

	@GetMapping("/close-checklist")
	public Map<String, Object> closeChecklist(@PathVariable long companyId, @RequestParam(required = false) Long periodId,
			@RequestParam(defaultValue = "false") boolean narrative) {
		return service.closeChecklist(companyId, periodId, narrative);
	}

	@GetMapping("/metrics")
	public Map<String, Object> metrics(@PathVariable long companyId) {
		return service.metrics(companyId);
	}
}
