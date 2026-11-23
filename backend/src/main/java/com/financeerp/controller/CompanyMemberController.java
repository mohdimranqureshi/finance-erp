package com.financeerp.controller;

import com.financeerp.dto.CompanyMemberDto;
import com.financeerp.service.CompanyMemberService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/members")
public class CompanyMemberController {
	private final CompanyMemberService service;

	public CompanyMemberController(CompanyMemberService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		return service.list(companyId);
	}

	@PostMapping
	public Map<String, Object> add(@PathVariable long companyId,
			@Valid @RequestBody CompanyMemberDto.AddMemberRequest request) {
		return service.add(companyId, request);
	}
}
