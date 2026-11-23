package com.financeerp.controller;

import com.financeerp.dto.IndiaSetupDto.ProfileRequest;
import com.financeerp.service.IndiaSetupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class IndiaSetupController {
	private final IndiaSetupService service;

	public IndiaSetupController(IndiaSetupService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/india/reference")
	public Map<String, Object> reference() {
		return service.reference();
	}

	@GetMapping("/api/v1/companies/{companyId}/india/profile")
	public Map<String, Object> profile(@PathVariable long companyId) {
		return service.profile(companyId);
	}

	@PutMapping("/api/v1/companies/{companyId}/india/profile")
	public Map<String, Object> updateProfile(@PathVariable long companyId, @Valid @RequestBody ProfileRequest req) {
		return service.updateProfile(companyId, req);
	}

	@PostMapping("/api/v1/companies/{companyId}/india/chart-of-accounts")
	public Map<String, Object> seedChart(@PathVariable long companyId) {
		return service.seedChart(companyId);
	}
}
