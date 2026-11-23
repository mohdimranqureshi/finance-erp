package com.financeerp.controller;

import com.financeerp.dto.BankReconciliationDto.ImportRequest;
import com.financeerp.dto.BankReconciliationDto.MatchRequest;
import com.financeerp.service.BankReconciliationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/bank")
public class BankReconciliationController {
	private final BankReconciliationService service;

	public BankReconciliationController(BankReconciliationService service) {
		this.service = service;
	}

	@PostMapping("/statement-lines")
	public Map<String, Object> importLines(@PathVariable long companyId, @Valid @RequestBody ImportRequest req) {
		return service.importLines(companyId, req);
	}

	@GetMapping("/statement-lines")
	public List<Map<String, Object>> lines(@PathVariable long companyId, @RequestParam long bankAccountId,
			@RequestParam(defaultValue = "false") boolean unmatchedOnly) {
		return service.lines(companyId, bankAccountId, unmatchedOnly);
	}

	@PostMapping("/statement-lines/{lineId}/match")
	public Map<String, Object> match(@PathVariable long companyId, @PathVariable long lineId,
			@Valid @RequestBody MatchRequest req) {
		return service.match(companyId, lineId, req);
	}

	@PostMapping("/statement-lines/{lineId}/unmatch")
	public Map<String, Object> unmatch(@PathVariable long companyId, @PathVariable long lineId) {
		return service.unmatch(companyId, lineId);
	}

	@GetMapping("/reconciliation")
	public Map<String, Object> reconciliation(@PathVariable long companyId, @RequestParam long bankAccountId,
			@RequestParam LocalDate asOf, @RequestParam(required = false) BigDecimal openingBalance) {
		return service.reconciliation(companyId, bankAccountId, asOf, openingBalance);
	}
}
