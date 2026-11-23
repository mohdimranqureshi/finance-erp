package com.financeerp.service;

import com.financeerp.dto.CompanyMemberDto;
import com.financeerp.repository.CompanyMemberRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class CompanyMemberService {
	private final CompanyMemberRepository members;
	private final CompanyAccess access;

	public CompanyMemberService(CompanyMemberRepository members, CompanyAccess access) {
		this.members = members;
		this.access = access;
	}

	public List<Map<String, Object>> list(long companyId) {
		long actorId = access.requireMembership(companyId);
		requireOwner(companyId, actorId);
		return members.findMembers(companyId);
	}

	@Transactional
	public Map<String, Object> add(long companyId, CompanyMemberDto.AddMemberRequest request) {
		long actorId = access.requireMembership(companyId);
		requireOwner(companyId, actorId);
		List<Map<String, Object>> users = members.findActiveUsersByEmail(request.email().trim());
		if (users.isEmpty())
			throw new IllegalArgumentException("No active user exists for that email. Ask them to register first.");
		long userId = ((Number) users.get(0).get("id")).longValue();
		members.insertMembership(companyId, userId, request.role().name());
		return members.findMember(companyId, userId);
	}

	private void requireOwner(long companyId, long userId) {
		if (members.countOwnerMemberships(companyId, userId) == 0)
			throw new AccessDeniedException("Only the company owner can manage members.");
	}
}
