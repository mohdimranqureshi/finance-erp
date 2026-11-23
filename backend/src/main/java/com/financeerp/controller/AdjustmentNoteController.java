package com.financeerp.controller;

import com.financeerp.dto.AdjustmentNoteDto.NoteRequest;
import com.financeerp.service.AdjustmentNoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Sales credit notes and purchase debit notes; each creates a DRAFT reversing journal. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class AdjustmentNoteController {
	private final AdjustmentNoteService service;

	public AdjustmentNoteController(AdjustmentNoteService service) {
		this.service = service;
	}

	@PostMapping("/sales-invoices/{invoiceId}/credit-notes")
	public Map<String, Object> creditNote(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody NoteRequest req) {
		return service.creditNote(companyId, invoiceId, req);
	}

	@PostMapping("/purchase-bills/{billId}/debit-notes")
	public Map<String, Object> debitNote(@PathVariable long companyId, @PathVariable long billId,
			@Valid @RequestBody NoteRequest req) {
		return service.debitNote(companyId, billId, req);
	}

	@GetMapping("/adjustment-notes")
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}
}
