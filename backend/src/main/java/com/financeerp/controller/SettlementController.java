package com.financeerp.controller;

import com.financeerp.dto.SettlementDto.PaymentRequest;
import com.financeerp.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Receipts and payments against invoices/bills and opening-balance party items. Each creates a DRAFT journal for
 * maker-checker approval.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class SettlementController {
	private final SettlementService service;

	public SettlementController(SettlementService service) {
		this.service = service;
	}

	@PostMapping("/sales-invoices/{invoiceId}/receipts")
	public Map<String, Object> receipt(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody PaymentRequest req) {
		return service.receipt(companyId, invoiceId, req);
	}

	@PostMapping("/opening-balances/items/{itemId}/receipts")
	public Map<String, Object> openingReceipt(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody PaymentRequest req) {
		return service.openingReceipt(companyId, itemId, req);
	}

	@PostMapping("/purchase-bills/{billId}/payments")
	public Map<String, Object> pay(@PathVariable long companyId, @PathVariable long billId,
			@Valid @RequestBody PaymentRequest req) {
		return service.pay(companyId, billId, req);
	}

	@PostMapping("/opening-balances/items/{itemId}/payments")
	public Map<String, Object> openingPayment(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody PaymentRequest req) {
		return service.openingPayment(companyId, itemId, req);
	}

	@GetMapping("/payments")
	public List<Map<String, Object>> payments(@PathVariable long companyId) {
		return service.payments(companyId);
	}

	@GetMapping("/open-items")
	public Map<String, Object> openItems(@PathVariable long companyId) {
		return service.openItems(companyId);
	}
}
