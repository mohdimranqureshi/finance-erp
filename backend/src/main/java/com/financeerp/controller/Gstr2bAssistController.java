package com.financeerp.controller;

import com.financeerp.service.Gstr2bAssistService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/gstr2b")
public class Gstr2bAssistController {
	private final Gstr2bAssistService service;

	public Gstr2bAssistController(Gstr2bAssistService service) {
		this.service = service;
	}

	/** Drafts (does not send) a follow-up email. With ai=true the model only phrases the greeting and closing. */
	@PostMapping("/imports/{importId}/vendor-follow-ups/{vendorId}/email")
	public Map<String, Object> followUpEmail(@PathVariable long companyId, @PathVariable long importId,
			@PathVariable long vendorId, @RequestParam(defaultValue = "false") boolean ai) {
		return service.followUpEmail(companyId, importId, vendorId, ai);
	}

	/** Turns a portal invoice that is missing from the books into a bill proposal (no LLM involved). */
	@PostMapping("/items/{itemId}/draft-bill")
	public Map<String, Object> draftBill(@PathVariable long companyId, @PathVariable long itemId) {
		return service.draftBill(companyId, itemId);
	}
}
