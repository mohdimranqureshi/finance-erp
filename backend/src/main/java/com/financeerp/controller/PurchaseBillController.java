package com.financeerp.controller;

import com.financeerp.dto.PurchaseBillDto.CreateRequest;
import com.financeerp.service.PurchaseBillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/purchase-bills")
public class PurchaseBillController {
	private final PurchaseBillService service;

	public PurchaseBillController(PurchaseBillService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@GetMapping("/{billId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long billId) {
		return service.get(companyId, billId);
	}

	@PostMapping("/{billId}/book")
	public Map<String, Object> book(@PathVariable long companyId, @PathVariable long billId) {
		return service.book(companyId, billId);
	}
}
