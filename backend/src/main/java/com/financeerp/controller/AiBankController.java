package com.financeerp.controller;

import com.financeerp.dto.AiBankDto.BankRequest;
import com.financeerp.service.AiBankService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiBankController {
	private final AiBankService service;

	public AiBankController(AiBankService service) {
		this.service = service;
	}

	@PostMapping("/categorise-bank-lines")
	public Map<String, Object> categorise(@PathVariable long companyId, @Valid @RequestBody BankRequest req) {
		return service.categorise(companyId, req);
	}

	@PostMapping("/reconcile-suggest")
	public Map<String, Object> reconcileSuggest(@PathVariable long companyId, @Valid @RequestBody BankRequest req) {
		return service.reconcileSuggest(companyId, req);
	}
}
