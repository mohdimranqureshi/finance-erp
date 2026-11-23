package com.financeerp.controller;

import com.financeerp.dto.CompanyDto;
import com.financeerp.service.CompanyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {
	private final CompanyService service;

	public CompanyController(CompanyService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@Valid @RequestBody CompanyDto.CreateCompanyRequest req) {
		return service.create(req);
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return service.list();
	}

	@GetMapping("/{companyId}/settings")
	public Map<String, Object> settings(@PathVariable long companyId) {
		return service.settings(companyId);
	}

	@PutMapping("/{companyId}/settings")
	public Map<String, Object> updateSettings(@PathVariable long companyId,
			@Valid @RequestBody CompanyDto.SettingsRequest req) {
		return service.updateSettings(companyId, req);
	}
}
