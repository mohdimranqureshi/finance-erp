package com.financeerp.controller;

import com.financeerp.service.ReportService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/reports")
public class ReportController {
	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
	}

	@GetMapping("/trial-balance")
	public List<Map<String, Object>> trialBalance(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return service.trialBalance(companyId, from, to);
	}

	@GetMapping("/ledger")
	public Map<String, Object> ledger(@PathVariable long companyId, @RequestParam long accountId,
			@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return service.ledger(companyId, accountId, from, to);
	}

	@GetMapping("/profit-and-loss")
	public Map<String, Object> profitAndLoss(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return service.profitAndLoss(companyId, from, to);
	}

	@GetMapping("/balance-sheet")
	public Map<String, Object> balanceSheet(@PathVariable long companyId, @RequestParam LocalDate asOf) {
		return service.balanceSheet(companyId, asOf);
	}

	@GetMapping("/gst-summary")
	public Map<String, Object> gstSummary(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return service.gstSummary(companyId, from, to);
	}
}
