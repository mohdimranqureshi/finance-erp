package com.financeerp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeerp.dto.Gstr2bDto.ResolveRequest;
import com.financeerp.service.Gstr2bService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/gstr2b")
public class Gstr2bController {
	private final Gstr2bService service;

	public Gstr2bController(Gstr2bService service) {
		this.service = service;
	}

	/** Accepts the GSTR-2B JSON downloaded from the GST portal (with or without the outer "data" wrapper). */
	@PostMapping("/imports")
	public Map<String, Object> importReturn(@PathVariable long companyId, @RequestBody JsonNode body) {
		return service.importReturn(companyId, body);
	}

	@GetMapping("/imports")
	public List<Map<String, Object>> imports(@PathVariable long companyId) {
		return service.imports(companyId);
	}

	@GetMapping("/imports/{importId}")
	public Map<String, Object> summary(@PathVariable long companyId, @PathVariable long importId) {
		return service.summary(companyId, importId);
	}

	/** Re-runs matching (e.g. after new bills or notes are entered); existing resolutions are kept. */
	@PostMapping("/imports/{importId}/reconcile")
	public Map<String, Object> reconcile(@PathVariable long companyId, @PathVariable long importId) {
		return service.reconcile(companyId, importId);
	}

	@GetMapping("/imports/{importId}/items")
	public List<Map<String, Object>> items(@PathVariable long companyId, @PathVariable long importId,
			@RequestParam(required = false) String status) {
		return service.items(companyId, importId, status);
	}

	/** Suppliers whose invoices or credit notes are in the books but not in GSTR-2B, ranked by ITC at risk. */
	@GetMapping("/imports/{importId}/vendor-follow-ups")
	public List<Map<String, Object>> vendorFollowUps(@PathVariable long companyId, @PathVariable long importId) {
		return service.vendorFollowUps(companyId, importId);
	}

	@PostMapping("/items/{itemId}/resolve")
	public Map<String, Object> resolve(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody ResolveRequest req) {
		return service.resolve(companyId, itemId, req);
	}

	@DeleteMapping("/imports/{importId}")
	public Map<String, Object> delete(@PathVariable long companyId, @PathVariable long importId) {
		return service.delete(companyId, importId);
	}
}
