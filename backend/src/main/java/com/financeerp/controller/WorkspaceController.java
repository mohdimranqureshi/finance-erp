package com.financeerp.controller;

import com.financeerp.dto.WorkspaceDto;
import com.financeerp.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
	private final WorkspaceService service;

	public WorkspaceController(WorkspaceService service) {
		this.service = service;
	}

	@GetMapping("/clients")
	public List<Map<String, Object>> clients(@RequestParam(required = false) String search,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "status") String sort) {
		return service.clients(search, status, sort);
	}

	@PutMapping("/clients/{companyId}/settings")
	public Map<String, Object> updateSettings(@PathVariable long companyId,
			@Valid @RequestBody WorkspaceDto.ClientSettingsRequest req) {
		return service.updateSettings(companyId, req);
	}

	@GetMapping("/approvals")
	public Map<String, Object> approvals(@RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) Long companyId) {
		return service.approvals(limit, offset, companyId);
	}

	@PostMapping("/approvals/bulk")
	public Map<String, Object> bulkApprove(@Valid @RequestBody WorkspaceDto.BulkApproveRequest req) {
		return service.bulkApprove(req);
	}
}
