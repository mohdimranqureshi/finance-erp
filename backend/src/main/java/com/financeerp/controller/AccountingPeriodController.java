package com.financeerp.controller;

import com.financeerp.dto.AccountingPeriodDto;
import com.financeerp.service.AccountingPeriodService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/periods")
public class AccountingPeriodController {
	private final AccountingPeriodService service;

	public AccountingPeriodController(AccountingPeriodService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId,
			@Valid @RequestBody AccountingPeriodDto.CreatePeriodRequest req) {
		return service.create(companyId, req);
	}

	@PostMapping("/{periodId}/close")
	public Map<String, Object> close(@PathVariable long companyId, @PathVariable long periodId) {
		return service.close(companyId, periodId);
	}
}
