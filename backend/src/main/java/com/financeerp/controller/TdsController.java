package com.financeerp.controller;

import com.financeerp.dto.TdsDto.AllocateRequest;
import com.financeerp.dto.TdsDto.ChallanRequest;
import com.financeerp.service.TdsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** TDS deducted on purchase bills: deposits (challans), allocation of deposits to deductions, and Form 26Q working data. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tds")
public class TdsController {
	private final TdsService service;

	public TdsController(TdsService service) {
		this.service = service;
	}

	@GetMapping("/deductions")
	public List<Map<String, Object>> deductions(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to, @RequestParam(defaultValue = "false") boolean unallocatedOnly) {
		return service.deductions(companyId, from, to, unallocatedOnly);
	}

	@PostMapping("/challans")
	public Map<String, Object> createChallan(@PathVariable long companyId, @Valid @RequestBody ChallanRequest req) {
		return service.createChallan(companyId, req);
	}

	@PostMapping("/challans/{challanId}/allocations")
	public Map<String, Object> allocate(@PathVariable long companyId, @PathVariable long challanId,
			@Valid @RequestBody AllocateRequest req) {
		return service.allocate(companyId, challanId, req);
	}

	@GetMapping("/challans")
	public List<Map<String, Object>> challans(@PathVariable long companyId) {
		return service.challans(companyId);
	}

	@GetMapping("/challans/{challanId}")
	public Map<String, Object> challan(@PathVariable long companyId, @PathVariable long challanId) {
		return service.challan(companyId, challanId);
	}

	@GetMapping("/26q")
	public Map<String, Object> form26q(@PathVariable long companyId, @RequestParam String fy, @RequestParam int quarter) {
		return service.form26q(companyId, fy, quarter);
	}
}
