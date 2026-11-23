package com.financeerp.service;

import com.financeerp.dto.AccountingPeriodDto;
import com.financeerp.repository.AccountingPeriodRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class AccountingPeriodService {
	private final AccountingPeriodRepository periods;
	private final CompanyAccess access;

	public AccountingPeriodService(AccountingPeriodRepository periods, CompanyAccess access) {
		this.periods = periods;
		this.access = access;
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return periods.findByCompany(companyId);
	}

	@Transactional
	public Map<String, Object> create(long companyId, AccountingPeriodDto.CreatePeriodRequest req) {
		long userId = access.requireMembership(companyId);
		requireOwnerOrApprover(companyId, userId);
		if (req.startDate().isAfter(req.endDate()))
			throw new IllegalArgumentException("startDate must be on or before endDate.");
		// Serialize period creation per company so concurrent requests cannot create
		// overlapping periods.
		periods.lockCompany(companyId);
		if (periods.countOverlapping(companyId, req.startDate(), req.endDate()) > 0)
			throw new IllegalArgumentException("Accounting periods cannot overlap.");
		return periods.insertPeriod(companyId, req.name().trim(), req.startDate(), req.endDate());
	}

	@Transactional
	public Map<String, Object> close(long companyId, long periodId) {
		long userId = access.requireMembership(companyId);
		requireOwnerOrApprover(companyId, userId);
		Map<String, Object> period = periods.lockPeriod(companyId, periodId)
				.orElseThrow(() -> new IllegalArgumentException("Accounting period not found."));
		if ("CLOSED".equals(period.get("status")))
			throw new IllegalArgumentException("Accounting period is already closed.");
		if (periods.countUnpostedJournals(companyId, period.get("startDate"), period.get("endDate")) > 0)
			throw new IllegalArgumentException(
					"Cannot close a period while it contains unposted journals. Complete their approval/posting workflow first.");
		periods.markClosed(companyId, periodId, userId);
		return periods.findPeriod(companyId, periodId);
	}

	private void requireOwnerOrApprover(long companyId, long userId) {
		if (periods.countOwnerOrApproverMemberships(companyId, userId) == 0)
			throw new AccessDeniedException("Only a company owner or approver can manage accounting periods.");
	}
}
