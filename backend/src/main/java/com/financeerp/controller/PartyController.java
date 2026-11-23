package com.financeerp.controller;

import com.financeerp.dto.PartyDto.CreatePartyRequest;
import com.financeerp.service.PartyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/parties")
public class PartyController {
	private final PartyService service;

	public PartyController(PartyService service) {
		this.service = service;
	}

	@PostMapping
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreatePartyRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId,
			@RequestParam(required = false) String type) {
		return service.list(companyId, type);
	}
}
