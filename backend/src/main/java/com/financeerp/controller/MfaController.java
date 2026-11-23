package com.financeerp.controller;

import com.financeerp.dto.MfaDto;
import com.financeerp.security.SessionValidationFilter;
import com.financeerp.service.MfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Self-service TOTP enrolment for the signed-in user. */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {
	private final MfaService mfa;

	public MfaController(MfaService mfa) {
		this.mfa = mfa;
	}

	/** Creates a pending secret; MFA is not active until {@code /enable} confirms a code from it. */
	@PostMapping("/setup")
	public MfaService.Setup setup(Authentication auth) {
		return mfa.beginSetup(auth.getName());
	}

	@PostMapping("/enable")
	public Map<String, Object> enable(@Valid @RequestBody MfaDto.EnableRequest request, Authentication auth,
			HttpServletRequest http) {
		List<String> codes = mfa.enable(auth.getName(), request.code());
		keepCurrentSession(http, auth.getName());
		return Map.of("mfaEnabled", true, "recoveryCodes", codes);
	}

	@PostMapping("/disable")
	public Map<String, Object> disable(@Valid @RequestBody MfaDto.DisableRequest request, Authentication auth,
			HttpServletRequest http) {
		mfa.disable(auth.getName(), request.password(), request.code());
		keepCurrentSession(http, auth.getName());
		return Map.of("mfaEnabled", false);
	}

	/** MFA changes revoke the user's other sessions by bumping session_version; this session stays signed in. */
	private void keepCurrentSession(HttpServletRequest http, String email) {
		HttpSession session = http.getSession(false);
		if (session == null || session.getAttribute(SessionValidationFilter.VERSION_ATTR) == null)
			return;
		session.setAttribute(SessionValidationFilter.VERSION_ATTR, mfa.sessionVersion(email));
	}
}
