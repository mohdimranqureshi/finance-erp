package com.financeerp.controller;

import com.financeerp.dto.AdminUserDto;
import com.financeerp.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Admin-only account administration. The ADMIN role is enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
	private final AdminUserService service;

	public AdminUserController(AdminUserService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		return service.list();
	}

	@PutMapping("/{userId}/status")
	public Map<String, Object> setStatus(@PathVariable long userId,
			@Valid @RequestBody AdminUserDto.StatusRequest request) {
		return service.setStatus(userId, request);
	}

	@PostMapping("/{userId}/disable")
	public Map<String, Object> disable(@PathVariable long userId) {
		return service.disable(userId);
	}

	@PostMapping("/{userId}/enable")
	public Map<String, Object> enable(@PathVariable long userId) {
		return service.enable(userId);
	}

	/** For a lost authenticator: removes TOTP and recovery codes and signs the user out everywhere. */
	@PostMapping("/{userId}/mfa/reset")
	public Map<String, Object> resetMfa(@PathVariable long userId) {
		return service.resetMfa(userId);
	}

	/** Deletes only accounts with no accounting footprint; everyone else must be deactivated. */
	@DeleteMapping("/{userId}")
	public Map<String, Object> delete(@PathVariable long userId) {
		return service.delete(userId);
	}
}
