package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/sales-invoices")
public class SalesInvoiceController {
	private static final String SELECT = """
			SELECT i.id,i.invoice_number AS "invoiceNumber",i.invoice_date AS "invoiceDate",i.due_date AS "dueDate",
			 i.customer_id AS "customerId",p.name AS "customerName",i.place_of_supply AS "placeOfSupply",
			 i.supply_type AS "supplyType",i.taxable_amount AS "taxableAmount",i.cgst_amount AS "cgstAmount",
			 i.sgst_amount AS "sgstAmount",i.igst_amount AS "igstAmount",i.total_amount AS "totalAmount",
			 i.status,i.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM sales_invoices i
			JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
			LEFT JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id
			""";
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public SalesInvoiceController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record LineRequest(@NotBlank @Size(max = 300) String description, @NotBlank String hsnSac,
			@NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice, @NotNull BigDecimal gstRate,
			Long revenueAccountId) {
	}

	public record CreateRequest(@NotNull Long customerId, @NotNull LocalDate invoiceDate, LocalDate dueDate,
			String placeOfSupply, @NotEmpty @Size(max = GstSupport.MAX_LINES) List<@Valid LineRequest> lines) {
	}

	@PostMapping
	@Transactional
	public Map<String, Object> create(@PathVariable long companyId, @Valid @RequestBody CreateRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		GstSupport.CompanyProfile company = gst.companyProfile(companyId);
		Map<String, Object> customer = gst.party(companyId, req.customerId(), "CUSTOMER");
		String pos = req.placeOfSupply() == null || req.placeOfSupply().isBlank() ? ((String) customer.get("stateCode")).trim()
				: req.placeOfSupply().trim();
		if (!IndiaTax.STATES.containsKey(pos))
			throw new IllegalArgumentException("Unknown place of supply state code.");
		if (req.dueDate() != null && req.dueDate().isBefore(req.invoiceDate()))
			throw new IllegalArgumentException("Due date cannot be before the invoice date.");
		boolean inter = !pos.equals(company.stateCode());
		long defaultRevenue = -1;
		List<IndiaTax.Taxed> taxed = new ArrayList<>();
		List<Long> accounts = new ArrayList<>();
		BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
		for (LineRequest l : req.lines()) {
			gst.checkLine(l.quantity(), l.unitPrice(), l.gstRate(), l.hsnSac(), req.invoiceDate(),
					company.gstRegistered());
			long account;
			if (l.revenueAccountId() != null) {
				gst.requireAccount(companyId, l.revenueAccountId(), "INCOME");
				account = l.revenueAccountId();
			} else {
				if (defaultRevenue < 0)
					defaultRevenue = poster.systemAccount(companyId, "SALES");
				account = defaultRevenue;
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
		if (total.signum() <= 0)
			throw new IllegalArgumentException("Invoice total must be greater than zero.");
		Long id = jdbc.queryForObject(
				"INSERT INTO sales_invoices(company_id,customer_id,invoice_date,due_date,place_of_supply,supply_type,taxable_amount,cgst_amount,sgst_amount,igst_amount,total_amount,created_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.customerId(), req.invoiceDate(), req.dueDate(), pos,
				inter ? "INTER_STATE" : "INTRA_STATE", taxable, cgst, sgst, igst, total, actor);
		for (int i = 0; i < req.lines().size(); i++) {
			LineRequest l = req.lines().get(i);
			IndiaTax.Taxed t = taxed.get(i);
			jdbc.update(
					"INSERT INTO sales_invoice_lines(company_id,invoice_id,description,hsn_sac,quantity,unit_price,gst_rate,taxable_amount,cgst_amount,sgst_amount,igst_amount,revenue_account_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
					companyId, id, l.description().trim(), l.hsnSac(), l.quantity(), l.unitPrice(), l.gstRate(),
					t.taxable(), t.cgst(), t.sgst(), t.igst(), accounts.get(i));
		}
		poster.audit(companyId, "SALES_INVOICE_CREATED", "SALES_INVOICE", id, "DRAFT");
		return get(companyId, id);
	}

	@GetMapping
	public List<Map<String, Object>> list(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(SELECT + " WHERE i.company_id=? ORDER BY i.invoice_date DESC,i.id DESC", companyId);
	}

	@GetMapping("/{invoiceId}")
	public Map<String, Object> get(@PathVariable long companyId, @PathVariable long invoiceId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE i.company_id=? AND i.id=?", companyId,
				invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", jdbc.queryForList(
				"SELECT id,description,hsn_sac AS \"hsnSac\",quantity,unit_price AS \"unitPrice\",gst_rate AS \"gstRate\",taxable_amount AS \"taxableAmount\",cgst_amount AS \"cgstAmount\",sgst_amount AS \"sgstAmount\",igst_amount AS \"igstAmount\",revenue_account_id AS \"revenueAccountId\" FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? ORDER BY id",
				companyId, invoiceId));
		return out;
	}

	/** Assigns the gapless invoice number and creates a DRAFT journal; the journal still needs independent approval. */
	@PostMapping("/{invoiceId}/issue")
	@Transactional
	public Map<String, Object> issue(@PathVariable long companyId, @PathVariable long invoiceId) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status,invoice_date AS \"invoiceDate\",taxable_amount AS \"taxable\",cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,total_amount AS total,customer_id AS \"customerId\" FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE",
				companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> inv = rows.get(0);
		if (!"DRAFT".equals(inv.get("status")))
			throw new IllegalArgumentException("Only draft invoices can be issued.");
		LocalDate date = ((java.sql.Date) inv.get("invoiceDate")).toLocalDate();
		poster.requireOpenPeriod(companyId, date);
		int seq = gst.nextNumber(companyId, "SALES_INVOICE", IndiaTax.fiscalYear(date));
		String number = String.format("INV/%s/%05d", IndiaTax.fiscalYear(date).substring(2), seq);
		List<LedgerPoster.Line> lines = new ArrayList<>();
		lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "AR"), number, (BigDecimal) inv.get("total"),
				BigDecimal.ZERO));
		jdbc.query(
				"SELECT revenue_account_id,SUM(taxable_amount) FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? GROUP BY revenue_account_id ORDER BY revenue_account_id",
				rs -> {
					lines.add(new LedgerPoster.Line(rs.getLong(1), number + " revenue", BigDecimal.ZERO,
							rs.getBigDecimal(2)));
				}, companyId, invoiceId);
		addTax(lines, companyId, "OUTPUT_CGST", (BigDecimal) inv.get("cgst"), number);
		addTax(lines, companyId, "OUTPUT_SGST", (BigDecimal) inv.get("sgst"), number);
		addTax(lines, companyId, "OUTPUT_IGST", (BigDecimal) inv.get("igst"), number);
		long journalId = poster.createDraftJournal(companyId, date, "Sales invoice " + number,
				"SALES_INVOICE:" + invoiceId, actor, lines);
		jdbc.update(
				"UPDATE sales_invoices SET status='ISSUED',invoice_number=?,journal_entry_id=?,issued_at=now() WHERE company_id=? AND id=?",
				number, journalId, companyId, invoiceId);
		poster.audit(companyId, "SALES_INVOICE_ISSUED", "SALES_INVOICE", invoiceId, number);
		return get(companyId, invoiceId);
	}

	private void addTax(List<LedgerPoster.Line> lines, long companyId, String key, BigDecimal amount, String number) {
		if (amount.signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, key), number + " " + key, BigDecimal.ZERO,
					amount));
	}
}
