package com.financeerp.service;

import com.financeerp.domain.OpeningBalancePlanner;
import com.financeerp.dto.OpeningBalanceDto.CreateRequest;
import com.financeerp.dto.OpeningBalanceDto.LineRequest;
import com.financeerp.dto.OpeningBalanceDto.PayableRequest;
import com.financeerp.dto.OpeningBalanceDto.ReceivableRequest;
import com.financeerp.repository.OpeningBalanceRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Opening balances: one batch per company producing a single DRAFT journal (GL lines, AR/AP from party items, the
 * difference to OPENING_BALANCE equity). A replacement batch is accepted only after the previous batch's journal has
 * been reversed and the reversal posted.
 */
@Service
public class OpeningBalanceService {
	private final OpeningBalanceRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public OpeningBalanceService(OpeningBalanceRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	@Transactional
	public Map<String, Object> create(long companyId, CreateRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		// Serialise batch creation per company.
		repository.lockCompany(companyId);
		List<Map<String, Object>> active = repository.findActiveBatches(companyId);
		for (Map<String, Object> prev : active) {
			if (!Boolean.TRUE.equals(prev.get("reversed")))
				throw new IllegalArgumentException("Opening balances were already entered (batch #" + prev.get("id")
						+ ", journal #" + prev.get("journalId") + " is " + prev.get("status")
						+ "). To replace them, post the journal, reverse it and post the reversal first.");
			repository.supersedeBatch(companyId, prev.get("id"));
		}

		OpeningBalancePlanner.Accounts accounts = new OpeningBalancePlanner.Accounts(poster.systemAccount(companyId, "AR"),
				poster.systemAccount(companyId, "AP"), poster.systemAccount(companyId, "OPENING_BALANCE"));
		List<OpeningBalancePlanner.GlLine> gl = new ArrayList<>();
		for (LineRequest l : nn(req.lines()))
			gl.add(l == null ? null : new OpeningBalancePlanner.GlLine(l.accountId(), l.debit(), l.credit(), l.memo()));
		List<OpeningBalancePlanner.PartyItem> rec = new ArrayList<>();
		for (ReceivableRequest r : nn(req.receivables()))
			rec.add(r == null ? null
					: new OpeningBalancePlanner.PartyItem(r.customerId(), r.documentNumber(), r.documentDate(), r.dueDate(), r.amount()));
		List<OpeningBalancePlanner.PartyItem> pay = new ArrayList<>();
		for (PayableRequest p : nn(req.payables()))
			pay.add(p == null ? null
					: new OpeningBalancePlanner.PartyItem(p.vendorId(), p.documentNumber(), p.documentDate(), p.dueDate(), p.amount()));
		OpeningBalancePlanner.Plan plan = OpeningBalancePlanner.plan(req.asOfDate(), gl, rec, pay, accounts);

		for (Long accountId : new LinkedHashSet<>(gl.stream().map(OpeningBalancePlanner.GlLine::accountId).toList())) {
			if (repository.countActiveAccount(companyId, accountId) == 0)
				throw new IllegalArgumentException("Account " + accountId + " is inactive or does not belong to this company.");
		}
		for (Long id : new LinkedHashSet<>(plan.receivables().stream().map(OpeningBalancePlanner.PartyItem::partyId).toList()))
			gst.party(companyId, id, "CUSTOMER");
		for (Long id : new LinkedHashSet<>(plan.payables().stream().map(OpeningBalancePlanner.PartyItem::partyId).toList()))
			gst.party(companyId, id, "VENDOR");
		poster.requireOpenPeriod(companyId, req.asOfDate());

		long journalId = poster.createDraftJournal(companyId, req.asOfDate(), "Opening balances as of " + req.asOfDate(),
				"OPENING:" + UUID.randomUUID(), actor, plan.lines());
		Long batchId = repository.insertBatch(companyId, req.asOfDate(), plan.receivableTotal(), plan.payableTotal(),
				plan.equityAdjustment(), journalId, actor);
		insertItems(companyId, batchId, "RECEIVABLE", plan.receivables());
		insertItems(companyId, batchId, "PAYABLE", plan.payables());
		poster.audit(companyId, "OPENING_BALANCES_CREATED", "OPENING_BALANCE_BATCH", batchId,
				"as of " + req.asOfDate() + "; journal " + journalId + "; " + gl.size() + " GL line(s), "
						+ plan.receivables().size() + " receivable(s) " + plan.receivableTotal().toPlainString() + ", "
						+ plan.payables().size() + " payable(s) " + plan.payableTotal().toPlainString()
						+ ", equity adjustment " + plan.equityAdjustment().toPlainString());
		return get(companyId);
	}

	/** The current batch (active, else the latest superseded one), its journal status and party items. */
	public Map<String, Object> get(long companyId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> batches = repository.findCurrentBatch(companyId);
		Map<String, Object> out = new LinkedHashMap<>();
		if (batches.isEmpty()) {
			out.put("batch", null);
			out.put("receivables", List.of());
			out.put("payables", List.of());
			return out;
		}
		Map<String, Object> batch = batches.get(0);
		out.put("batch", batch);
		out.put("receivables", repository.findItems(companyId, ((Number) batch.get("id")).longValue(), "RECEIVABLE"));
		out.put("payables", repository.findItems(companyId, ((Number) batch.get("id")).longValue(), "PAYABLE"));
		return out;
	}

	private void insertItems(long companyId, long batchId, String type, List<OpeningBalancePlanner.PartyItem> items) {
		for (OpeningBalancePlanner.PartyItem i : items)
			repository.insertItem(companyId, batchId, type, i.partyId(), i.documentNumber(), i.documentDate(),
					i.dueDate(), i.amount());
	}

	private static <T> List<T> nn(List<T> list) {
		return list == null ? List.of() : list;
	}
}
