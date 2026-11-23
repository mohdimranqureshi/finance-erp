package com.financeerp.controller;

import com.financeerp.dto.CompanyInvitationDto;
import com.financeerp.service.CompanyInvitationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CompanyInvitationController {
	private final CompanyInvitationService service;

	public CompanyInvitationController(CompanyInvitationService service) {
		this.service = service;
	}

	@PostMapping("/companies/{companyId}/invitations")
	public Map<String, Object> create(@PathVariable long companyId,
			@Valid @RequestBody CompanyInvitationDto.CreateInvitationRequest request) {
		return service.create(companyId, request);
	}

	@PostMapping("/auth/accept-invitation")
	public Map<String, Object> accept(@Valid @RequestBody CompanyInvitationDto.AcceptInvitationRequest request) {
		return service.accept(request);
	}
}
