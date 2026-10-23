package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/purchase-bills")
public class PurchaseBillController {
	private static final String SELECT = """
			SELECT b.id,b.vendor_invoice_number AS "vendorInvoiceNumber",b.bill_date AS "billDate",b.due_date AS "dueDate",
			 b.vendor_id AS "vendorId",p.name AS "vendorName",b.place_of_supply AS "placeOfSupply",
			 b.supply_type AS "supplyType",b.taxable_amount AS "taxableAmount",b.cgst_amount AS "cgstAmount",
			 b.sgst_amount AS "sgstAmount",b.igst_amount AS "igstAmount",b.total_amount AS "totalAmount",
			 b.itc_eligible AS "itcEligible",b.tds_section AS "tdsSection",b.tds_rate AS "tdsRate",
			 b.tds_amount AS "tdsAmount",b.status,b.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM purchase_bills b
			JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id
			LEFT JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
			""";
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public PurchaseBillController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record LineRequest(@NotBlank @Size(max = 300) String description, @NotBlank String hsnSac,
			@NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice, @NotNull BigDecimal gstRate,
			Long expenseAccountId) {
	}

	public record CreateRequest(@NotNull Long vendorId, @NotBlank @Size(max = 50) String vendorInvoiceNumber,
			@NotNull LocalDate billDate, LocalDate dueDate, Boolean itcEligible,
			@Pattern(regexp = "[0-9A-Z]{2,10}") String tdsSection, BigDecimal tdsRate,
			@NotEmpty @Size(max = GstSupport.MAX_LINES) List<@Valid LineRequest> lines) {
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		GstSupport.CompanyProfile company = gst.companyProfile(companyId);
		Map<String, Object> vendor = gst.party(companyId, req.vendorId(), "VENDOR");
		boolean vendorRegistered = vendor.get("gstin") != null;
		if (req.dueDate() != null && req.dueDate().isBefore(req.billDate()))
			throw new IllegalArgumentException("Due date cannot be before the bill date.");
		boolean inter = !company.stateCode().equals(((String) vendor.get("stateCode")).trim());
		boolean itc = company.gstRegistered() && (req.itcEligible() == null || req.itcEligible());
		if (req.tdsSection() != null && (req.tdsRate() == null || req.tdsRate().signum() <= 0
				|| req.tdsRate().compareTo(BigDecimal.valueOf(30)) > 0 || req.tdsRate().scale() > 2))
			throw new IllegalArgumentException("TDS rate must be above 0 and at most 30, with at most two decimals.");
		if (req.tdsSection() == null && req.tdsRate() != null)
			throw new IllegalArgumentException("Provide a TDS section together with the TDS rate.");
		Integer duplicate = jdbc.queryForObject(
				"SELECT count(*) FROM purchase_bills WHERE company_id=? AND vendor_id=? AND lower(vendor_invoice_number)=lower(?)",
				Integer.class, companyId, req.vendorId(), req.vendorInvoiceNumber().trim());
		if (duplicate != null && duplicate > 0)
			throw new IllegalArgumentException("This vendor invoice number has already been recorded (possible duplicate bill).");
		long defaultExpense = -1;
		List<IndiaTax.Taxed> taxed = new ArrayList<>();
		List<Long> accounts = new ArrayList<>();
		BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
		for (LineRequest l : req.lines()) {
			gst.checkLine(l.quantity(), l.unitPrice(), l.gstRate(), l.hsnSac(), req.billDate(), vendorRegistered);
			long account;
			if (l.expenseAccountId() != null) {
				gst.requireAccount(companyId, l.expenseAccountId(), "EXPENSE");
				account = l.expenseAccountId();
			} else {
				if (defaultExpense < 0)
					defaultExpense = poster.systemAccount(companyId, "PURCHASES");
				account = defaultExpense;
			}
			IndiaTax.Taxed t = IndiaTax.computeLine(l.quantity(), l.unitPrice(), l.gstRate(), inter);
			if (t.taxable().signum() == 0)
				throw new IllegalArgumentException("Line value rounds to zero: " + l.description());
			taxed.add(t);
			accounts.add(account);
			taxable = taxable.add(t.taxable());
			cgst = cgst.add(t.cgst());
			sgst = sgst.add(t.sgst());
			igst = igst.add(t.igst());
		}
		BigDecimal total = taxable.add(cgst).add(sgst).add(igst);
		BigDecimal tds = req.tdsSection() == null ? BigDecimal.ZERO.setScale(2) : IndiaTax.tds(taxable, req.tdsRate());
		if (total.signum() <= 0)
			throw new IllegalArgumentException("Bill total must be greater than zero.");
		Long id = jdbc.queryForObject(
				"INSERT INTO purchase_bills(company_id,vendor_id,vendor_invoice_number,bill_date,due_date,place_of_supply,supply_type,taxable_amount,cgst_amount,sgst_amount,igst_amount,total_amount,itc_eligible,tds_section,tds_rate,tds_amount,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.vendorId(), req.vendorInvoiceNumber().trim(), req.billDate(), req.dueDate(),
				company.stateCode(), inter ? "INTER_STATE" : "INTRA_STATE", taxable, cgst, sgst, igst, total, itc,
				req.tdsSection(), req.tdsRate(), tds, actor);
		for (int i = 0; i < req.lines().size(); i++) {
			LineRequest l = req.lines().get(i);
			IndiaTax.Taxed t = taxed.get(i);
			jdbc.update(
					"INSERT INTO purchase_bill_lines(company_id,bill_id,description,hsn_sac,quantity,unit_price,gst_rate,taxable_amount,cgst_amount,sgst_amount,igst_amount,expense_account_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
					companyId, id, l.description().trim(), l.hsnSac(), l.quantity(), l.unitPrice(), l.gstRate(),
					t.taxable(), t.cgst(), t.sgst(), t.igst(), accounts.get(i));
		}
		poster.audit(companyId, "PURCHASE_BILL_CREATED", "PURCHASE_BILL", id, "DRAFT");
		return get(companyId, id);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(SELECT + " WHERE b.company_id=? ORDER BY b.bill_date DESC,b.id DESC", companyId);
	}

	@GetMapping("/{billId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long billId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE b.company_id=? AND b.id=?", companyId,
				billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", jdbc.queryForList(
				"SELECT id,description,hsn_sac AS \"hsnSac\",quantity,unit_price AS \"unitPrice\",gst_rate AS \"gstRate\",taxable_amount AS \"taxableAmount\",cgst_amount AS \"cgstAmount\",sgst_amount AS \"sgstAmount\",igst_amount AS \"igstAmount\",expense_account_id AS \"expenseAccountId\" FROM purchase_bill_lines WHERE company_id=? AND bill_id=? ORDER BY id",
				companyId, billId));
		return out;
	}

	/** Creates a DRAFT journal; the journal still needs independent approval before it reaches the ledger. */
	@PostMapping("/{billId}/book")
	@Transactional
	public Map<String, Object> book(@PathVariable long companyId, @PathVariable long billId) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status,bill_date AS \"billDate\",vendor_invoice_number AS ref,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,total_amount AS total,tds_amount AS tds,itc_eligible AS itc FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE",
				companyId, billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		Map<String, Object> bill = rows.get(0);
		if (!"DRAFT".equals(bill.get("status")))
			throw new IllegalArgumentException("Only draft bills can be booked.");
		LocalDate date = ((java.sql.Date) bill.get("billDate")).toLocalDate();
		poster.requireOpenPeriod(companyId, date);
		boolean itc = (Boolean) bill.get("itc");
		BigDecimal cgst = (BigDecimal) bill.get("cgst"), sgst = (BigDecimal) bill.get("sgst"),
				igst = (BigDecimal) bill.get("igst"), total = (BigDecimal) bill.get("total"),
				tds = (BigDecimal) bill.get("tds");
		String ref = "Bill " + bill.get("ref");
		List<LedgerPoster.Line> lines = new ArrayList<>();
		// Without eligible ITC, the GST is a cost and is added to each line's expense.
		jdbc.query(
				"SELECT expense_account_id,SUM(taxable_amount),SUM(cgst_amount+sgst_amount+igst_amount) FROM purchase_bill_lines WHERE company_id=? AND bill_id=? GROUP BY expense_account_id ORDER BY expense_account_id",
				rs -> {
					BigDecimal amount = itc ? rs.getBigDecimal(2) : rs.getBigDecimal(2).add(rs.getBigDecimal(3));
					lines.add(new LedgerPoster.Line(rs.getLong(1), ref, amount, BigDecimal.ZERO));
				}, companyId, billId);
		if (itc) {
			addInput(lines, companyId, "INPUT_CGST", cgst, ref);
			addInput(lines, companyId, "INPUT_SGST", sgst, ref);
			addInput(lines, companyId, "INPUT_IGST", igst, ref);
		}
		if (tds.signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "TDS_PAYABLE"), ref + " TDS",
					BigDecimal.ZERO, tds));
		lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "AP"), ref, BigDecimal.ZERO,
				total.subtract(tds)));
		long journalId = poster.createDraftJournal(companyId, date, ref, "PURCHASE_BILL:" + billId, actor, lines);
		jdbc.update(
				"UPDATE purchase_bills SET status='BOOKED',journal_entry_id=?,booked_at=now() WHERE company_id=? AND id=?",
				journalId, companyId, billId);
		poster.audit(companyId, "PURCHASE_BILL_BOOKED", "PURCHASE_BILL", billId, String.valueOf(bill.get("ref")));
		return get(companyId, billId);
	}

	private void addInput(List<LedgerPoster.Line> lines, long companyId, String key, BigDecimal amount, String ref) {
		if (amount.signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, key), ref + " " + key, amount,
					BigDecimal.ZERO));
	}
}
