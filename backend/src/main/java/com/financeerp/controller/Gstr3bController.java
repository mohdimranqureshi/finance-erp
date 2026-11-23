package com.financeerp.controller;

import com.financeerp.service.Gstr3bService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/gst")
public class Gstr3bController {
	private final Gstr3bService service;

	public Gstr3bController(Gstr3bService service) {
		this.service = service;
	}

	/** period is MMYYYY; itcBasis is "books" (default) or "gstr2b" (forward-charge ITC as available in GSTR-2B). */
	@GetMapping("/gstr3b")
	public Map<String, Object> gstr3b(@PathVariable long companyId, @RequestParam String period,
			@RequestParam(defaultValue = "books") String itcBasis) {
		return service.gstr3b(companyId, period, itcBasis);
	}
}
