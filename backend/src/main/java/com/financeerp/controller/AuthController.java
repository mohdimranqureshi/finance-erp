package com.financeerp.controller;

import com.financeerp.dto.AuthDto;
import com.financeerp.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private final AuthService service;

	public AuthController(AuthService service) {
		this.service = service;
	}

	/** Admin only (enforced in SecurityConfig). */
	@PostMapping("/register")
	public Map<String, Object> register(@Valid @RequestBody AuthDto.RegisterRequest request, Authentication admin) {
		return service.register(request, admin.getName());
	}

	@GetMapping("/me")
	public Map<String, Object> me(Authentication auth) {
		return service.me(auth);
	}
}
