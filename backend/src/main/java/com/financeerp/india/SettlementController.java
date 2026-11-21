package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Receipts and payments against invoices/bills and opening-balance party items. Each creates a DRAFT journal for
 * maker-checker approval.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class SettlementController {
	private static final String INVOICE_OUTSTANDING = """
			i.total_amount
			 - COALESCE((SELECT SUM(x.amount+x.tds_amount) FROM payments x WHERE x.company_id=i.company_id AND x.sales_invoice_id=i.id),0)
			 - COALESCE((SELECT SUM(n.total_amount) FROM adjustment_notes n WHERE n.company_id=i.company_id AND n.sales_invoice_id=i.id),0)
			""";
	private static final String BILL_OUTSTANDING = """
			b.total_amount - b.tds_amount
			 - COALESCE((SELECT SUM(x.amount) FROM payments x WHERE x.company_id=b.company_id AND x.purchase_bill_id=b.id),0)
			 - COALESCE((SELECT SUM(n.total_amount) FROM adjustment_notes n WHERE n.company_id=b.company_id AND n.purchase_bill_id=b.id),0)
			""";
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public SettlementController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster, GstSupport gst) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	public record PaymentRequest(@NotNull LocalDate paymentDate, @NotNull BigDecimal amount, BigDecimal tdsAmount,
			Long bankAccountId, @Size(max = 100) String reference) {
	}

	@PostMapping("/sales-invoices/{invoiceId}/receipts")
	@Transactional
	public Map<String, Object> receipt(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE", companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		if (!"ISSUED".equals(rows.get(0).get("status")))
			throw new IllegalArgumentException("Only issued invoices can receive payments.");
		return recordReceipt(companyId, actor, req, () -> jdbc.queryForObject("SELECT " + INVOICE_OUTSTANDING
				+ " FROM sales_invoices i WHERE i.company_id=? AND i.id=?", BigDecimal.class, companyId, invoiceId),
				"Receipt for invoice #" + invoiceId, "sales_invoice_id", invoiceId, "invoice " + invoiceId);
	}

	/** Receipt against an opening receivable; same rules as an invoice receipt (TDS deducted by customer allowed). */
	@PostMapping("/opening-balances/items/{itemId}/receipts")
	@Transactional
	public Map<String, Object> openingReceipt(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		String number = lockOpeningItem(companyId, itemId, "RECEIVABLE");
		return recordReceipt(companyId, actor, req, () -> openingOutstanding(companyId, itemId),
				"Receipt for opening receivable " + number + " (item #" + itemId + ")", "opening_item_id", itemId,
				"opening item " + itemId);
	}

	private Map<String, Object> recordReceipt(long companyId, long actor, PaymentRequest req,
			Supplier<BigDecimal> outstandingQuery, String ref, String documentColumn, long documentId, String auditDetail) {
		BigDecimal tds = req.tdsAmount() == null ? BigDecimal.ZERO : req.tdsAmount();
		gst.checkAmount(req.amount(), 13, "Amount");
		gst.checkAmount(tds, 13, "TDS amount");
		if (req.amount().signum() <= 0)
			throw new IllegalArgumentException("Amount must be greater than zero.");
		BigDecimal settled = req.amount().add(tds);
		BigDecimal outstanding = outstandingQuery.get();
		if (settled.compareTo(outstanding) > 0)
			throw new IllegalArgumentException("Receipt plus TDS (" + settled.toPlainString()
					+ ") exceeds the outstanding balance (" + outstanding.toPlainString() + ").");
		poster.requireOpenPeriod(companyId, req.paymentDate());
		long bank = bankAccount(companyId, req.bankAccountId());
		List<LedgerPoster.Line> lines = new ArrayList<>();
		lines.add(new LedgerPoster.Line(bank, ref, req.amount(), BigDecimal.ZERO));
		if (tds.signum() > 0)
			lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "TDS_RECEIVABLE"), ref + " TDS deducted by customer",
					tds, BigDecimal.ZERO));
		lines.add(new LedgerPoster.Line(poster.systemAccount(companyId, "AR"), ref, BigDecimal.ZERO, settled));
		long journalId = poster.createDraftJournal(companyId, req.paymentDate(), ref, "PMT:" + UUID.randomUUID(), actor,
				lines);
		// documentColumn is one of the fixed column names passed by this class, never user input.
		Long id = jdbc.queryForObject(
				"INSERT INTO payments(company_id,direction,payment_date,amount,tds_amount,bank_account_id,reference,"
						+ documentColumn + ",journal_entry_id,created_by_user_id) VALUES (?,'RECEIPT',?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.paymentDate(), req.amount(), tds, bank, req.reference(), documentId, journalId,
				actor);
		poster.audit(companyId, "RECEIPT_RECORDED", "PAYMENT", id, auditDetail);
		return payment(companyId, id);
	}

	@PostMapping("/purchase-bills/{billId}/payments")
	@Transactional
	public Map<String, Object> pay(@PathVariable long companyId, @PathVariable long billId,
			@Valid @RequestBody PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE", companyId, billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		if (!"BOOKED".equals(rows.get(0).get("status")))
			throw new IllegalArgumentException("Only booked bills can be paid.");
		if (req.tdsAmount() != null && req.tdsAmount().signum() != 0)
			throw new IllegalArgumentException("TDS on a bill is recorded when the bill is created; do not enter it on payment.");
		return recordPayment(companyId, actor, req, () -> jdbc.queryForObject("SELECT " + BILL_OUTSTANDING
				+ " FROM purchase_bills b WHERE b.company_id=? AND b.id=?", BigDecimal.class, companyId, billId),
				"Payment for bill #" + billId, "purchase_bill_id", billId, "bill " + billId);
	}

	/** Payment against an opening payable; the carried-forward balance is already net of any TDS, as for bills. */
	@PostMapping("/opening-balances/items/{itemId}/payments")
	@Transactional
	public Map<String, Object> openingPayment(@PathVariable long companyId, @PathVariable long itemId,
			@Valid @RequestBody PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		String number = lockOpeningItem(companyId, itemId, "PAYABLE");
		if (req.tdsAmount() != null && req.tdsAmount().signum() != 0)
			throw new IllegalArgumentException(
					"An opening payable is carried forward net of TDS; do not enter TDS on payment.");
		return recordPayment(companyId, actor, req, () -> openingOutstanding(companyId, itemId),
				"Payment for opening payable " + number + " (item #" + itemId + ")", "opening_item_id", itemId,
				"opening item " + itemId);
	}

	private Map<String, Object> recordPayment(long companyId, long actor, PaymentRequest req,
			Supplier<BigDecimal> outstandingQuery, String ref, String documentColumn, long documentId, String auditDetail) {
		gst.checkAmount(req.amount(), 13, "Amount");
		if (req.amount().signum() <= 0)
			throw new IllegalArgumentException("Amount must be greater than zero.");
		BigDecimal outstanding = outstandingQuery.get();
		if (req.amount().compareTo(outstanding) > 0)
			throw new IllegalArgumentException(
					"Payment exceeds the outstanding balance (" + outstanding.toPlainString() + ").");
		poster.requireOpenPeriod(companyId, req.paymentDate());
		long bank = bankAccount(companyId, req.bankAccountId());
		long journalId = poster.createDraftJournal(companyId, req.paymentDate(), ref, "PMT:" + UUID.randomUUID(), actor,
				List.of(new LedgerPoster.Line(poster.systemAccount(companyId, "AP"), ref, req.amount(), BigDecimal.ZERO),
						new LedgerPoster.Line(bank, ref, BigDecimal.ZERO, req.amount())));
		// documentColumn is one of the fixed column names passed by this class, never user input.
		Long id = jdbc.queryForObject(
				"INSERT INTO payments(company_id,direction,payment_date,amount,tds_amount,bank_account_id,reference,"
						+ documentColumn + ",journal_entry_id,created_by_user_id) VALUES (?,'PAYMENT',?,?,0,?,?,?,?,?) RETURNING id",
				Long.class, companyId, req.paymentDate(), req.amount(), bank, req.reference(), documentId, journalId, actor);
		poster.audit(companyId, "PAYMENT_RECORDED", "PAYMENT", id, auditDetail);
		return payment(companyId, id);
	}

	/** Locks a settleable opening item of the given type and returns its document number. */
	private String lockOpeningItem(long companyId, long itemId, String type) {
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT o.item_type AS \"itemType\",o.document_number AS \"number\","
				+ OpeningBalanceController.ITEM_SETTLEABLE + " AS settleable FROM " + OpeningBalanceController.ITEM_JOIN
				+ " WHERE o.company_id=? AND o.id=? FOR UPDATE OF o", companyId, itemId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Opening balance item not found.");
		Map<String, Object> row = rows.get(0);
		if (!type.equals(row.get("itemType")))
			throw new IllegalArgumentException("RECEIVABLE".equals(type)
					? "Receipts can only be recorded against opening receivables."
					: "Payments can only be recorded against opening payables.");
		if (!Boolean.TRUE.equals(row.get("settleable")))
			throw new IllegalArgumentException(
					"This opening balance item belongs to a reversed or superseded opening batch and cannot be settled.");
		return (String) row.get("number");
	}

	private BigDecimal openingOutstanding(long companyId, long itemId) {
		return jdbc.queryForObject("SELECT " + OpeningBalanceController.ITEM_OUTSTANDING
				+ " FROM opening_balance_items o WHERE o.company_id=? AND o.id=?", BigDecimal.class, companyId, itemId);
	}

	@GetMapping("/payments")
	public List<Map<String, Object>> payments(@PathVariable long companyId) {
		access.requireMembership(companyId);
		return jdbc.queryForList(PAYMENT_SELECT + " WHERE p.company_id=? ORDER BY p.payment_date DESC,p.id DESC",
				companyId);
	}

	private static final String PAYMENT_SELECT = """
			SELECT p.id,p.direction,p.payment_date AS "paymentDate",p.amount,p.tds_amount AS "tdsAmount",
			 p.bank_account_id AS "bankAccountId",p.reference,p.sales_invoice_id AS "salesInvoiceId",
			 p.purchase_bill_id AS "purchaseBillId",p.opening_item_id AS "openingItemId",
			 p.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id
			""";

	private Map<String, Object> payment(long companyId, long id) {
		return jdbc.queryForMap(PAYMENT_SELECT + " WHERE p.company_id=? AND p.id=?", companyId, id);
	}

	private long bankAccount(long companyId, Long requested) {
		if (requested == null)
			return poster.systemAccount(companyId, "BANK");
		gst.requireAccount(companyId, requested, "ASSET");
		return requested;
	}

	/**
	 * Open receivables and payables with ageing buckets as of today. Invoice/bill rows carry their document id;
	 * opening-balance rows have documentType OPENING_BALANCE, a null id and openingItemId (settle them through
	 * /opening-balances/items/{openingItemId}/receipts or /payments).
	 */
	@GetMapping("/open-items")
	public Map<String, Object> openItems(@PathVariable long companyId) {
		access.requireMembership(companyId);
		LocalDate today = LocalDate.now();
		List<Map<String, Object>> receivables = aged(jdbc.queryForList("SELECT i.id,i.invoice_number AS \"number\",p.name AS party,i.invoice_date AS \"date\",i.due_date AS \"dueDate\",i.total_amount AS \"totalAmount\","
				+ INVOICE_OUTSTANDING + " AS outstanding,'INVOICE' AS \"documentType\",NULL::bigint AS \"openingItemId\" FROM sales_invoices i JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id WHERE i.company_id=? AND i.status='ISSUED'"
				+ " UNION ALL " + openingItems("RECEIVABLE") + " ORDER BY 4,8,1,9",
				companyId, companyId), today);
		List<Map<String, Object>> payables = aged(jdbc.queryForList("SELECT b.id,b.vendor_invoice_number AS \"number\",p.name AS party,b.bill_date AS \"date\",b.due_date AS \"dueDate\",b.total_amount AS \"totalAmount\","
				+ BILL_OUTSTANDING + " AS outstanding,'BILL' AS \"documentType\",NULL::bigint AS \"openingItemId\" FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id WHERE b.company_id=? AND b.status='BOOKED'"
				+ " UNION ALL " + openingItems("PAYABLE") + " ORDER BY 4,8,1,9",
				companyId, companyId), today);
		return Map.of("receivables", receivables, "payables", payables, "totalReceivable", total(receivables),
				"totalPayable", total(payables));
	}

	private static String openingItems(String type) {
		return "SELECT NULL::bigint,o.document_number,p.name,o.document_date,o.due_date,o.amount," + OpeningBalanceController.ITEM_OUTSTANDING
				+ ",'OPENING_BALANCE',o.id FROM " + OpeningBalanceController.ITEM_JOIN
				+ " JOIN parties p ON p.company_id=o.company_id AND p.id=o.party_id WHERE o.company_id=? AND o.item_type='" + type
				+ "' AND " + OpeningBalanceController.ITEM_SETTLEABLE;
	}

	private static BigDecimal total(List<Map<String, Object>> rows) {
		return rows.stream().map(r -> (BigDecimal) r.get("outstanding")).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static List<Map<String, Object>> aged(List<Map<String, Object>> rows, LocalDate today) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			if (((BigDecimal) r.get("outstanding")).signum() <= 0)
				continue;
			java.sql.Date due = (java.sql.Date) (r.get("dueDate") != null ? r.get("dueDate") : r.get("date"));
			long days = Math.max(0, ChronoUnit.DAYS.between(due.toLocalDate(), today));
			Map<String, Object> row = new LinkedHashMap<>(r);
			row.put("daysOverdue", days);
			row.put("bucket", days == 0 ? "CURRENT" : days <= 30 ? "1-30" : days <= 60 ? "31-60" : days <= 90 ? "61-90" : "90+");
			out.add(row);
		}
		return out;
	}
}
