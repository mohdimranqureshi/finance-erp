package com.financeerp.controller;

import com.financeerp.dto.JournalDto;
import com.financeerp.service.JournalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/journals")
public class JournalController {
	private final JournalService service;

	public JournalController(JournalService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId,
			@Valid @RequestBody JournalDto.CreateJournalRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@GetMapping("/{journalId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long journalId) {
		return service.get(companyId, journalId);
	}

	@PostMapping("/{journalId}/submit")
	public Map<String, Object> submit(@PathVariable long companyId, @PathVariable long journalId) {
		return service.submit(companyId, journalId);
	}

	@PostMapping("/{journalId}/approve")
	public Map<String, Object> approve(@PathVariable long companyId, @PathVariable long journalId) {
		return service.approve(companyId, journalId);
	}

	@PostMapping("/{journalId}/post")
	public Map<String, Object> post(@PathVariable long companyId, @PathVariable long journalId) {
		return service.post(companyId, journalId);
	}

	@PostMapping("/{journalId}/reverse")
	public Map<String, Object> reverse(@PathVariable long companyId, @PathVariable long journalId,
			@Valid @RequestBody JournalDto.ReverseJournalRequest request) {
		return service.reverse(companyId, journalId, request);
	}
}
