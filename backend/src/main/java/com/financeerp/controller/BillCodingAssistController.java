package com.financeerp.controller;

import com.financeerp.dto.BillCodingAssistDto;
import com.financeerp.service.BillCodingAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Keyword-rule coding suggestions. Proposals only: nothing is created, approved or posted from here. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class BillCodingAssistController {
	private final BillCodingAssistService service;

	public BillCodingAssistController(BillCodingAssistService service) {
		this.service = service;
	}

	@PostMapping("/suggest-bill-coding")
	public Map<String, Object> suggest(@PathVariable long companyId, @Valid @RequestBody BillCodingAssistDto.SuggestRequest req) {
		return service.suggest(companyId, req);
	}
}
