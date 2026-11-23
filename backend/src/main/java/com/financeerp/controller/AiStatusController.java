package com.financeerp.controller;

import com.financeerp.service.AiStatusService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiStatusController {
	private final AiStatusService service;

	public AiStatusController(AiStatusService service) {
		this.service = service;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return service.status();
	}
}
