package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** TDS deducted on purchase bills: deposits (challans), allocation of deposits to deductions, and Form 26Q working data. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tds")
public class TdsController {
	private static final Set<String> EDITORS = Set.of("OWNER", "ACCOUNTANT");
	/** Booked bills with TDS whose journal is posted and not reversed. */
	private static final String DEDUCTIONS = """
			SELECT b.id AS "billId",b.vendor_invoice_number AS "billNumber",b.bill_date AS "deductionDate",
			 p.id AS "vendorId",p.name AS "vendorName",p.pan,b.tds_section AS section,b.tds_rate AS rate,
			 b.taxable_amount AS "amountPaidOrCredited",b.tds_amount AS "tdsDeducted",
			 COALESCE((SELECT SUM(a.amount) FROM tds_challan_allocations a WHERE a.company_id=b.company_id AND a.bill_id=b.id),0) AS allocated
			FROM purchase_bills b
			JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
			JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id AND j.status='POSTED'
			LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
			WHERE b.company_id=? AND b.tds_amount>0 AND (r.id IS NULL OR r.status<>'POSTED')
			""";
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public TdsController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record Allocation(@NotNull Long billId, @NotNull BigDecimal amount) {
	}

	/** expenseAccountId receives interest and late fee; it is required when either is non-zero. */
	public record ChallanRequest(@NotNull @Pattern(regexp = "[0-9]{7}") String bsrCode,
			@NotNull @Pattern(regexp = "[0-9]{1,5}") String challanSerial, @NotNull LocalDate depositDate,
			@NotNull BigDecimal taxAmount, BigDecimal interestAmount, BigDecimal feeAmount, @NotNull Long bankAccountId,
			Long expenseAccountId, @Size(max = 500) List<@Valid Allocation> allocations) {
	}

	public record AllocateRequest(@NotNull @Size(min = 1, max = 500) List<@Valid Allocation> allocations) {
	}

	@GetMapping("/deductions")
	public List<Map<String, Object>> deductions(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to, @RequestParam(defaultValue = "false") boolean unallocatedOnly) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> d : jdbc.queryForList(
				DEDUCTIONS + " AND b.bill_date BETWEEN ? AND ? ORDER BY b.bill_date,b.id", companyId, from, to)) {
			Map<String, Object> row = enrich(d);
			if (!unallocatedOnly || ((BigDecimal) row.get("unallocated")).signum() > 0)
				out.add(row);
		}
		return out;
	}

	/** Records a deposit and creates a DRAFT journal (Dr TDS payable, Dr expense for interest/fee, Cr bank). */
	@PostMapping("/challans")
	@Transactional
	public Map<String, Object> createChallan(@PathVariable long companyId, @Valid @RequestBody ChallanRequest req) {
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
		List<LedgerPoster.Line> lines = new ArrayList<>();
		if (req.taxAmount().signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "TDS_PAYABLE"), ref, req.taxAmount(), BigDecimal.ZERO));
		if (interest.add(fee).signum() > 0)
			lines.add(new LedgerPoster.Line(req.expenseAccountId(), ref + " interest/fee", interest.add(fee), BigDecimal.ZERO));
		lines.add(new LedgerPoster.Line(req.bankAccountId(), ref, BigDecimal.ZERO, total));
		long journalId = poster.createDraftJournal(companyId, req.depositDate(), ref,
				"TDS_CHALLAN:" + req.bsrCode() + ":" + req.depositDate() + ":" + req.challanSerial(), actor, lines);
		Long id = jdbc.queryForObject(
				"INSERT INTO tds_challans(company_id,bsr_code,challan_serial,deposit_date,tax_amount,interest_amount,fee_amount,bank_account_id,journal_entry_id,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.bsrCode(), req.challanSerial(), req.depositDate(), req.taxAmount(), interest, fee,
				req.bankAccountId(), journalId, actor);
		if (req.allocations() != null && !req.allocations().isEmpty())
			allocateInternal(companyId, id, req.allocations(), actor);
		poster.audit(companyId, "TDS_CHALLAN_RECORDED", "TDS_CHALLAN", id, ref);
		return challan(companyId, id);
	}

	@PostMapping("/challans/{challanId}/allocations")
	@Transactional
	public Map<String, Object> allocate(@PathVariable long companyId, @PathVariable long challanId,
			@Valid @RequestBody AllocateRequest req) {
		long actor = access.requireRole(companyId, EDITORS);
		allocateInternal(companyId, challanId, req.allocations(), actor);
		poster.audit(companyId, "TDS_CHALLAN_ALLOCATED", "TDS_CHALLAN", challanId, req.allocations().size() + " deductions");
		return challan(companyId, challanId);
	}

	@GetMapping("/challans")
	public List<Map<String, Object>> challans(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(CHALLAN_SELECT + " WHERE c.company_id=? ORDER BY c.deposit_date DESC,c.id DESC", companyId);
	}

	@GetMapping("/challans/{challanId}")
	public Map<String, Object> challan(@PathVariable long companyId, @PathVariable long challanId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(CHALLAN_SELECT + " WHERE c.company_id=? AND c.id=?", companyId,
				challanId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Challan not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("allocations", jdbc.queryForList("""
				SELECT a.bill_id AS "billId",b.vendor_invoice_number AS "billNumber",p.name AS "vendorName",a.amount
				FROM tds_challan_allocations a
				JOIN purchase_bills b ON b.company_id=a.company_id AND b.id=a.bill_id
				JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
				WHERE a.company_id=? AND a.challan_id=? ORDER BY a.id""", companyId, challanId));
		return out;
	}

	/** Form 26Q (non-salary TDS) working data for a quarter; not the NSDL/TRACES file format. */
	@GetMapping("/26q")
	public Map<String, Object> form26q(@PathVariable long companyId, @RequestParam String fy, @RequestParam int quarter) {
		access.requireMembership(companyId);
		LocalDate from = TdsRules.quarterStart(fy, quarter), to = from.plusMonths(3).minusDays(1);
		Map<String, Object> company = jdbc.queryForMap("SELECT name,tan,pan FROM companies WHERE id=?", companyId);
		List<String> problems = new ArrayList<>();
		if (company.get("tan") == null)
			problems.add("Company TAN is missing: set it in the India profile.");
		Map<Long, List<Map<String, Object>>> allocationsByBill = new HashMap<>();
		for (Map<String, Object> a : jdbc.queryForList("""
				SELECT a.bill_id AS "billId",a.amount,c.bsr_code AS "bsrCode",c.challan_serial AS "challanSerial",
				 c.deposit_date AS "depositDate"
				FROM tds_challan_allocations a JOIN tds_challans c ON c.company_id=a.company_id AND c.id=a.challan_id
				WHERE a.company_id=? ORDER BY c.deposit_date,a.id""", companyId))
			allocationsByBill.computeIfAbsent(((Number) a.get("billId")).longValue(), k -> new ArrayList<>()).add(a);

		List<Map<String, Object>> deductees = new ArrayList<>();
		BigDecimal totalTds = BigDecimal.ZERO, totalInterest = BigDecimal.ZERO;
		for (Map<String, Object> d : jdbc.queryForList(DEDUCTIONS + " AND b.bill_date BETWEEN ? AND ? ORDER BY b.bill_date,b.id",
				companyId, from, to)) {
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
		out.put("challans", jdbc.queryForList(CHALLAN_SELECT
				+ " WHERE c.company_id=? AND c.id IN (SELECT a.challan_id FROM tds_challan_allocations a JOIN purchase_bills b ON b.company_id=a.company_id AND b.id=a.bill_id WHERE a.company_id=? AND b.bill_date BETWEEN ? AND ?) ORDER BY c.deposit_date",
				companyId, companyId, from, to));
		out.put("totals", Map.of("tdsDeducted", totalTds, "indicativeLateDepositInterest", totalInterest));
		out.put("problems", problems);
		out.put("note", "Working data only: prepare the 26Q file with the NSDL Return Preparation Utility and validate it with the FVU. "
				+ "Section codes, lower-deduction certificates, 206AB and salary (24Q) are not handled.");
		return out;
	}

	private static final String CHALLAN_SELECT = """
			SELECT c.id,c.bsr_code AS "bsrCode",c.challan_serial AS "challanSerial",c.deposit_date AS "depositDate",
			 c.tax_amount AS "taxAmount",c.interest_amount AS "interestAmount",c.fee_amount AS "feeAmount",
			 c.journal_entry_id AS "journalEntryId",j.status AS "journalStatus",
			 COALESCE((SELECT SUM(a.amount) FROM tds_challan_allocations a WHERE a.company_id=c.company_id AND a.challan_id=c.id),0) AS allocated
			FROM tds_challans c JOIN journal_entries j ON j.company_id=c.company_id AND j.id=c.journal_entry_id
			""";

	private void allocateInternal(long companyId, long challanId, List<Allocation> allocations, long actor) {
		List<Map<String, Object>> challans = jdbc.queryForList(
				"SELECT tax_amount AS tax,deposit_date AS date FROM tds_challans WHERE company_id=? AND id=? FOR UPDATE",
				companyId, challanId);
		if (challans.isEmpty())
			throw new IllegalArgumentException("Challan not found.");
		LocalDate depositDate = ((java.sql.Date) challans.get(0).get("date")).toLocalDate();
		BigDecimal remaining = ((BigDecimal) challans.get(0).get("tax")).subtract(jdbc.queryForObject(
				"SELECT COALESCE(SUM(amount),0) FROM tds_challan_allocations WHERE company_id=? AND challan_id=?",
				BigDecimal.class, companyId, challanId));
		Set<Long> seen = new HashSet<>();
		for (Allocation a : allocations) {
			if (!seen.add(a.billId()))
				throw new IllegalArgumentException("Bill " + a.billId() + " appears more than once.");
			gst.checkAmount(a.amount(), 13, "Allocation");
			if (a.amount().signum() <= 0)
				throw new IllegalArgumentException("Allocation amounts must be greater than zero.");
			jdbc.queryForList("SELECT id FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE", companyId, a.billId());
			List<Map<String, Object>> bills = jdbc.queryForList(DEDUCTIONS + " AND b.id=?", companyId, a.billId());
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
			jdbc.update(
					"INSERT INTO tds_challan_allocations(company_id,challan_id,bill_id,amount,created_by_user_id) VALUES (?,?,?,?,?) ON CONFLICT (company_id,challan_id,bill_id) DO UPDATE SET amount=tds_challan_allocations.amount+EXCLUDED.amount",
					companyId, challanId, a.billId(), a.amount(), actor);
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
