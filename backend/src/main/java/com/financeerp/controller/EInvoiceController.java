package com.financeerp.controller;

import com.financeerp.dto.EInvoiceDto.IrnRequest;
import com.financeerp.dto.EInvoiceDto.TransportRequest;
import com.financeerp.service.EInvoiceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/sales-invoices/{invoiceId}")
public class EInvoiceController {
	private final EInvoiceService service;

	public EInvoiceController(EInvoiceService service) {
		this.service = service;
	}

	@GetMapping("/e-invoice-payload")
	public Map<String, Object> eInvoice(@PathVariable long companyId, @PathVariable long invoiceId) {
		return service.eInvoice(companyId, invoiceId);
	}

	@PostMapping("/irn")
	public Map<String, Object> recordIrn(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody IrnRequest req) {
		return service.recordIrn(companyId, invoiceId, req);
	}

	@PostMapping("/e-way-bill-payload")
	public Map<String, Object> eWayBill(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody TransportRequest t) {
		return service.eWayBill(companyId, invoiceId, t);
	}
}
