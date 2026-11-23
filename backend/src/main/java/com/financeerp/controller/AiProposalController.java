package com.financeerp.controller;

import com.financeerp.dto.AiProposalDto.AcceptRequest;
import com.financeerp.dto.AiProposalDto.ExtractRequest;
import com.financeerp.dto.AiProposalDto.JournalRequest;
import com.financeerp.service.AiProposalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiProposalController {
	private final AiProposalService service;

	public AiProposalController(AiProposalService service) {
		this.service = service;
	}

	@PostMapping("/extract-bill")
	public Map<String, Object> extractBill(@PathVariable long companyId, @Valid @RequestBody ExtractRequest req) {
		return service.extractBill(companyId, req.text());
	}

	@PostMapping("/extract-invoice")
	public Map<String, Object> extractInvoice(@PathVariable long companyId, @Valid @RequestBody ExtractRequest req) {
		return service.extractInvoice(companyId, req.text());
	}

	@PostMapping("/draft-journal")
	public Map<String, Object> draftJournal(@PathVariable long companyId, @Valid @RequestBody JournalRequest req) {
		return service.draftJournal(companyId, req);
	}

	@GetMapping("/proposals")
	public List<Map<String, Object>> list(@PathVariable long companyId,
			@RequestParam(required = false) String status) {
		return service.list(companyId, status);
	}

	@PostMapping("/proposals/{proposalId}/accept")
	public Map<String, Object> accept(@PathVariable long companyId, @PathVariable long proposalId,
			@Valid @RequestBody(required = false) AcceptRequest req) {
		return service.accept(companyId, proposalId, req);
	}

	@PostMapping("/proposals/{proposalId}/reject")
	public Map<String, Object> reject(@PathVariable long companyId, @PathVariable long proposalId) {
		return service.reject(companyId, proposalId);
	}
}
