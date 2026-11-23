package com.financeerp.controller;

import com.financeerp.service.GstReturnService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/gst")
public class GstReturnController {
	private final GstReturnService service;

	public GstReturnController(GstReturnService service) {
		this.service = service;
	}

	@GetMapping("/gstr1")
	public Map<String, Object> gstr1(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		return service.gstr1(companyId, from, to);
	}
}
