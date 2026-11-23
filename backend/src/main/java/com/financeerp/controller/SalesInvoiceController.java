package com.financeerp.controller;

import com.financeerp.dto.SalesInvoiceDto.CreateRequest;
import com.financeerp.service.SalesInvoiceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/sales-invoices")
public class SalesInvoiceController {
	private final SalesInvoiceService service;

	public SalesInvoiceController(SalesInvoiceService service) {
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

	@GetMapping("/{invoiceId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long invoiceId) {
		return service.get(companyId, invoiceId);
	}

	@PostMapping("/{invoiceId}/issue")
	public Map<String, Object> issue(@PathVariable long companyId, @PathVariable long invoiceId) {
		return service.issue(companyId, invoiceId);
	}
}
