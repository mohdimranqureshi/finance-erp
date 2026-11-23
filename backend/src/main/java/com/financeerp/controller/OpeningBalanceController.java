package com.financeerp.controller;

import com.financeerp.dto.OpeningBalanceDto.CreateRequest;
import com.financeerp.service.OpeningBalanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/opening-balances")
public class OpeningBalanceController {
	private final OpeningBalanceService service;

	public OpeningBalanceController(OpeningBalanceService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping
	public Map<String, Object> get(@PathVariable long companyId) {
		return service.get(companyId);
	}
}
