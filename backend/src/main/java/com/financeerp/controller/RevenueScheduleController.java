package com.financeerp.controller;

import com.financeerp.dto.RevenueScheduleDto.RecognizeRequest;
import com.financeerp.dto.RevenueScheduleDto.ScheduleRequest;
import com.financeerp.service.RevenueScheduleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Straight-line monthly recognition; a full month is recognised from the start month. Journals are drafts. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class RevenueScheduleController {
	private final RevenueScheduleService service;

	public RevenueScheduleController(RevenueScheduleService service) {
		this.service = service;
	}

	@PostMapping("/sales-invoices/{invoiceId}/revenue-schedule")
	public Map<String, Object> create(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody ScheduleRequest req) {
		return service.create(companyId, invoiceId, req);
	}

	@PostMapping("/revenue-schedules/recognize")
	public Map<String, Object> recognize(@PathVariable long companyId, @Valid @RequestBody RecognizeRequest req) {
		return service.recognize(companyId, req);
	}

	@GetMapping("/revenue-schedules")
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@GetMapping("/revenue-schedules/{scheduleId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long scheduleId) {
		return service.get(companyId, scheduleId);
	}
}
