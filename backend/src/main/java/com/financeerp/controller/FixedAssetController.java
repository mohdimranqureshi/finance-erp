package com.financeerp.controller;

import com.financeerp.dto.FixedAssetDto.CreateRequest;
import com.financeerp.dto.FixedAssetDto.DisposeRequest;
import com.financeerp.dto.FixedAssetDto.RunRequest;
import com.financeerp.service.FixedAssetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class FixedAssetController {
	private final FixedAssetService service;

	public FixedAssetController(FixedAssetService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/fixed-assets/categories")
	public Map<String, Object> categories() {
		return service.categories();
	}

	@PostMapping("/api/v1/companies/{companyId}/fixed-assets")
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		return service.create(companyId, req);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets")
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/{assetId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long assetId) {
		return service.get(companyId, assetId);
	}

	@PostMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs")
	public Map<String, Object> run(@PathVariable long companyId, @Valid @RequestBody RunRequest req) {
		return service.run(companyId, req);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs")
	public List<Map<String, Object>> runs(@PathVariable long companyId) {
		return service.runs(companyId);
	}

	@GetMapping("/api/v1/companies/{companyId}/fixed-assets/depreciation-runs/{runId}")
	public Map<String, Object> getRun(@PathVariable long companyId, @PathVariable long runId) {
		return service.getRun(companyId, runId);
	}

	@PostMapping("/api/v1/companies/{companyId}/fixed-assets/{assetId}/dispose")
	public Map<String, Object> dispose(@PathVariable long companyId, @PathVariable long assetId,
			@Valid @RequestBody DisposeRequest req) {
		return service.dispose(companyId, assetId, req);
	}
}
