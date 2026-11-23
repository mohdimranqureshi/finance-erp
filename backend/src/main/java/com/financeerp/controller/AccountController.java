package com.financeerp.controller;

import com.financeerp.dto.AccountDto;
import com.financeerp.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/accounts")
public class AccountController {
	private final AccountService service;

	public AccountController(AccountService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId,
			@Valid @RequestBody AccountDto.CreateAccountRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}
}
