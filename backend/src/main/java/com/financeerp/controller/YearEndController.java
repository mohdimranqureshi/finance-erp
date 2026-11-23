package com.financeerp.controller;

import com.financeerp.dto.YearEndDto.CloseRequest;
import com.financeerp.service.YearEndService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/year-end")
public class YearEndController {
	private final YearEndService service;

	public YearEndController(YearEndService service) {
		this.service = service;
	}

	@PostMapping("/closing-journal")
	public Map<String, Object> closingJournal(@PathVariable long companyId, @Valid @RequestBody CloseRequest req) {
		return service.closingJournal(companyId, req);
	}
}
