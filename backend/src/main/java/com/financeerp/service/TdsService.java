package com.financeerp.service;

import com.financeerp.domain.LedgerLine;
import com.financeerp.domain.TdsRules;
import com.financeerp.dto.TdsDto.AllocateRequest;
import com.financeerp.dto.TdsDto.Allocation;
import com.financeerp.dto.TdsDto.ChallanRequest;
import com.financeerp.repository.TdsRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** TDS deducted on purchase bills: deposits (challans), allocation of deposits to deductions, and Form 26Q working data. */
@Service
public class TdsService {
	private static final Set<String> EDITORS = Set.of("OWNER", "ACCOUNTANT");
	private final TdsRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public TdsService(TdsRepository repository, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public List<Map<String, Object>> deductions(long companyId, LocalDate from, LocalDate to, boolean unallocatedOnly) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> d : repository.findDeductions(companyId, from, to)) {
			Map<String, Object> row = enrich(d);
			if (!unallocatedOnly || ((BigDecimal) row.get("unallocated")).signum() > 0)
				out.add(row);
		}
		return out;
	}

	/** Records a deposit and creates a DRAFT journal (Dr TDS payable, Dr expense for interest/fee, Cr bank). */
	@Transactional
	public Map<String, Object> createChallan(long companyId, ChallanRequest req) {
		long actor = access.requireRole(companyId, EDITORS);
		BigDecimal interest = req.interestAmount() == null ? BigDecimal.ZERO : req.interestAmount();
		BigDecimal fee = req.feeAmount() == null ? BigDecimal.ZERO : req.feeAmount();
		gst.checkAmount(req.taxAmount(), 13, "Tax amount");
		gst.checkAmount(interest, 13, "Interest");
		gst.checkAmount(fee, 13, "Fee");
		BigDecimal total = req.taxAmount().add(interest).add(fee);
		if (total.signum() <= 0)
			throw new IllegalArgumentException("Challan amount must be greater than zero.");
		if (req.depositDate().isAfter(LocalDate.now()))
			throw new IllegalArgumentException("Deposit date cannot be in the future.");
		gst.requireAccount(companyId, req.bankAccountId(), "ASSET");
		if (interest.add(fee).signum() > 0) {
			if (req.expenseAccountId() == null)
				throw new IllegalArgumentException("Choose an expense account for interest and late fee.");
			gst.requireAccount(companyId, req.expenseAccountId(), "EXPENSE");
		}
		poster.requireOpenPeriod(companyId, req.depositDate());
		String ref = "TDS challan " + req.bsrCode() + "/" + req.challanSerial() + " " + req.depositDate();
		List<LedgerLine> lines = new ArrayList<>();
		if (req.taxAmount().signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, "TDS_PAYABLE"), ref, req.taxAmount(), BigDecimal.ZERO));
		if (interest.add(fee).signum() > 0)
			lines.add(new LedgerLine(req.expenseAccountId(), ref + " interest/fee", interest.add(fee), BigDecimal.ZERO));
		lines.add(new LedgerLine(req.bankAccountId(), ref, BigDecimal.ZERO, total));
		long journalId = poster.createDraftJournal(companyId, req.depositDate(), ref,
				"TDS_CHALLAN:" + req.bsrCode() + ":" + req.depositDate() + ":" + req.challanSerial(), actor, lines);
		Long id = repository.insertChallan(companyId, req.bsrCode(), req.challanSerial(), req.depositDate(),
				req.taxAmount(), interest, fee, req.bankAccountId(), journalId, actor);
		if (req.allocations() != null && !req.allocations().isEmpty())
			allocateInternal(companyId, id, req.allocations(), actor);
		poster.audit(companyId, "TDS_CHALLAN_RECORDED", "TDS_CHALLAN", id, ref);
		return challan(companyId, id);
	}

	@Transactional
	public Map<String, Object> allocate(long companyId, long challanId, AllocateRequest req) {
		long actor = access.requireRole(companyId, EDITORS);
		allocateInternal(companyId, challanId, req.allocations(), actor);
		poster.audit(companyId, "TDS_CHALLAN_ALLOCATED", "TDS_CHALLAN", challanId, req.allocations().size() + " deductions");
		return challan(companyId, challanId);
	}

	public List<Map<String, Object>> challans(long companyId) {
		access.requireMembership(companyId);
		return repository.findChallans(companyId);
	}

	public Map<String, Object> challan(long companyId, long challanId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findChallan(companyId, challanId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Challan not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("allocations", repository.findChallanAllocations(companyId, challanId));
		return out;
	}

	/** Form 26Q (non-salary TDS) working data for a quarter; not the NSDL/TRACES file format. */
	public Map<String, Object> form26q(long companyId, String fy, int quarter) {
		access.requireMembership(companyId);
		LocalDate from = TdsRules.quarterStart(fy, quarter), to = from.plusMonths(3).minusDays(1);
		Map<String, Object> company = repository.findDeductor(companyId);
		List<String> problems = new ArrayList<>();
		if (company.get("tan") == null)
			problems.add("Company TAN is missing: set it in the India profile.");
		Map<Long, List<Map<String, Object>>> allocationsByBill = new HashMap<>();
		for (Map<String, Object> a : repository.findAllocationsWithDeposits(companyId))
			allocationsByBill.computeIfAbsent(((Number) a.get("billId")).longValue(), k -> new ArrayList<>()).add(a);

		List<Map<String, Object>> deductees = new ArrayList<>();
		BigDecimal totalTds = BigDecimal.ZERO, totalInterest = BigDecimal.ZERO;
		for (Map<String, Object> d : repository.findDeductions(companyId, from, to)) {
			Map<String, Object> row = enrich(d);
			LocalDate deducted = ((java.sql.Date) d.get("deductionDate")).toLocalDate();
			List<Map<String, Object>> deposits = allocationsByBill.getOrDefault(((Number) d.get("billId")).longValue(), List.of());
			BigDecimal interest = BigDecimal.ZERO;
			for (Map<String, Object> dep : deposits)
				interest = interest.add(TdsRules.lateDepositInterest((BigDecimal) dep.get("amount"), deducted,
						((java.sql.Date) dep.get("depositDate")).toLocalDate()));
			row.put("deposits", deposits);
			row.put("indicativeLateDepositInterest", interest);
			if (d.get("pan") == null)
				problems.add("Bill " + d.get("billNumber") + " (" + d.get("vendorName")
						+ "): vendor PAN missing; section 206AA may require TDS at 20% or twice the rate.");
			if (((BigDecimal) row.get("unallocated")).signum() > 0)
				problems.add("Bill " + d.get("billNumber") + ": TDS of " + row.get("unallocated") + " is not linked to a challan.");
			totalTds = totalTds.add((BigDecimal) d.get("tdsDeducted"));
			totalInterest = totalInterest.add(interest);
			deductees.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("financialYear", fy);
		out.put("quarter", quarter);
		out.put("from", from);
		out.put("to", to);
		out.put("returnDueDate", TdsRules.returnDueDate(fy, quarter));
		out.put("deductor", company);
		out.put("deductees", deductees);
		out.put("challans", repository.findChallansAllocatedToBillsBetween(companyId, from, to));
		out.put("totals", Map.of("tdsDeducted", totalTds, "indicativeLateDepositInterest", totalInterest));
		out.put("problems", problems);
		out.put("note", "Working data only: prepare the 26Q file with the NSDL Return Preparation Utility and validate it with the FVU. "
				+ "Section codes, lower-deduction certificates, 206AB and salary (24Q) are not handled.");
		return out;
	}

	private void allocateInternal(long companyId, long challanId, List<Allocation> allocations, long actor) {
		List<Map<String, Object>> challans = repository.lockChallan(companyId, challanId);
		if (challans.isEmpty())
			throw new IllegalArgumentException("Challan not found.");
		LocalDate depositDate = ((java.sql.Date) challans.get(0).get("date")).toLocalDate();
		BigDecimal remaining = ((BigDecimal) challans.get(0).get("tax"))
				.subtract(repository.sumAllocations(companyId, challanId));
		Set<Long> seen = new HashSet<>();
		for (Allocation a : allocations) {
			if (!seen.add(a.billId()))
				throw new IllegalArgumentException("Bill " + a.billId() + " appears more than once.");
			gst.checkAmount(a.amount(), 13, "Allocation");
			if (a.amount().signum() <= 0)
				throw new IllegalArgumentException("Allocation amounts must be greater than zero.");
			repository.lockPurchaseBill(companyId, a.billId());
			List<Map<String, Object>> bills = repository.findDeductionByBill(companyId, a.billId());
			if (bills.isEmpty())
				throw new IllegalArgumentException("Bill " + a.billId() + " has no posted TDS deduction.");
			Map<String, Object> bill = enrich(bills.get(0));
			if (((java.sql.Date) bill.get("deductionDate")).toLocalDate().isAfter(depositDate))
				throw new IllegalArgumentException("Bill " + a.billId() + " was deducted after this challan was deposited.");
			if (a.amount().compareTo((BigDecimal) bill.get("unallocated")) > 0)
				throw new IllegalArgumentException("Allocation for bill " + a.billId() + " exceeds its unallocated TDS of "
						+ bill.get("unallocated") + ".");
			remaining = remaining.subtract(a.amount());
			if (remaining.signum() < 0)
				throw new IllegalArgumentException("Allocations exceed the challan's tax amount.");
			repository.upsertAllocation(companyId, challanId, a.billId(), a.amount(), actor);
		}
	}

	private static Map<String, Object> enrich(Map<String, Object> d) {
		Map<String, Object> row = new LinkedHashMap<>(d);
		BigDecimal unallocated = ((BigDecimal) d.get("tdsDeducted")).subtract((BigDecimal) d.get("allocated"));
		row.put("unallocated", unallocated);
		row.put("depositDueDate", TdsRules.depositDueDate(((java.sql.Date) d.get("deductionDate")).toLocalDate()));
		row.put("panMissing", d.get("pan") == null);
		return row;
	}
}
