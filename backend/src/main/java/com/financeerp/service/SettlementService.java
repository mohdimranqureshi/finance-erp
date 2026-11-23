package com.financeerp.service;

import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.SettlementDto.PaymentRequest;
import com.financeerp.repository.SettlementRepository;
import com.financeerp.repository.SettlementRepository.PaymentDocument;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Receipts and payments against invoices/bills and opening-balance party items. Each creates a DRAFT journal for
 * maker-checker approval.
 */
@Service
public class SettlementService {
	private final SettlementRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public SettlementService(SettlementRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	@Transactional
	public Map<String, Object> receipt(long companyId, long invoiceId, PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = repository.lockInvoiceStatus(companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		if (!"ISSUED".equals(rows.get(0).get("status")))
			throw new IllegalArgumentException("Only issued invoices can receive payments.");
		return recordReceipt(companyId, actor, req, () -> repository.invoiceOutstanding(companyId, invoiceId),
				"Receipt for invoice #" + invoiceId, PaymentDocument.SALES_INVOICE, invoiceId, "invoice " + invoiceId);
	}

	/** Receipt against an opening receivable; same rules as an invoice receipt (TDS deducted by customer allowed). */
	@Transactional
	public Map<String, Object> openingReceipt(long companyId, long itemId, PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		String number = lockOpeningItem(companyId, itemId, "RECEIVABLE");
		return recordReceipt(companyId, actor, req, () -> repository.openingOutstanding(companyId, itemId),
				"Receipt for opening receivable " + number + " (item #" + itemId + ")", PaymentDocument.OPENING_ITEM,
				itemId, "opening item " + itemId);
	}

	private Map<String, Object> recordReceipt(long companyId, long actor, PaymentRequest req,
			Supplier<BigDecimal> outstandingQuery, String ref, PaymentDocument document, long documentId,
			String auditDetail) {
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
		List<LedgerLine> lines = new ArrayList<>();
		lines.add(new LedgerLine(bank, ref, req.amount(), BigDecimal.ZERO));
		if (tds.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, "TDS_RECEIVABLE"), ref + " TDS deducted by customer",
					tds, BigDecimal.ZERO));
		lines.add(new LedgerLine(poster.systemAccount(companyId, "AR"), ref, BigDecimal.ZERO, settled));
		long journalId = poster.createDraftJournal(companyId, req.paymentDate(), ref, "PMT:" + UUID.randomUUID(), actor,
				lines);
		Long id = repository.insertReceipt(companyId, req.paymentDate(), req.amount(), tds, bank, req.reference(),
				document, documentId, journalId, actor);
		poster.audit(companyId, "RECEIPT_RECORDED", "PAYMENT", id, auditDetail);
		return repository.findPayment(companyId, id);
	}

	@Transactional
	public Map<String, Object> pay(long companyId, long billId, PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = repository.lockBillStatus(companyId, billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		if (!"BOOKED".equals(rows.get(0).get("status")))
			throw new IllegalArgumentException("Only booked bills can be paid.");
		if (req.tdsAmount() != null && req.tdsAmount().signum() != 0)
			throw new IllegalArgumentException("TDS on a bill is recorded when the bill is created; do not enter it on payment.");
		return recordPayment(companyId, actor, req, () -> repository.billOutstanding(companyId, billId),
				"Payment for bill #" + billId, PaymentDocument.PURCHASE_BILL, billId, "bill " + billId);
	}

	/** Payment against an opening payable; the carried-forward balance is already net of any TDS, as for bills. */
	@Transactional
	public Map<String, Object> openingPayment(long companyId, long itemId, PaymentRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		String number = lockOpeningItem(companyId, itemId, "PAYABLE");
		if (req.tdsAmount() != null && req.tdsAmount().signum() != 0)
			throw new IllegalArgumentException(
					"An opening payable is carried forward net of TDS; do not enter TDS on payment.");
		return recordPayment(companyId, actor, req, () -> repository.openingOutstanding(companyId, itemId),
				"Payment for opening payable " + number + " (item #" + itemId + ")", PaymentDocument.OPENING_ITEM,
				itemId, "opening item " + itemId);
	}

	private Map<String, Object> recordPayment(long companyId, long actor, PaymentRequest req,
			Supplier<BigDecimal> outstandingQuery, String ref, PaymentDocument document, long documentId,
			String auditDetail) {
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
				List.of(new LedgerLine(poster.systemAccount(companyId, "AP"), ref, req.amount(), BigDecimal.ZERO),
						new LedgerLine(bank, ref, BigDecimal.ZERO, req.amount())));
		Long id = repository.insertPayment(companyId, req.paymentDate(), req.amount(), bank, req.reference(),
				document, documentId, journalId, actor);
		poster.audit(companyId, "PAYMENT_RECORDED", "PAYMENT", id, auditDetail);
		return repository.findPayment(companyId, id);
	}

	/** Locks a settleable opening item of the given type and returns its document number. */
	private String lockOpeningItem(long companyId, long itemId, String type) {
		List<Map<String, Object>> rows = repository.lockOpeningItem(companyId, itemId);
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

	public List<Map<String, Object>> payments(long companyId) {
		access.requireMembership(companyId);
		return repository.findPayments(companyId);
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
	public Map<String, Object> openItems(long companyId) {
		access.requireMembership(companyId);
		LocalDate today = LocalDate.now();
		List<Map<String, Object>> receivables = aged(repository.findOpenReceivables(companyId), today);
		List<Map<String, Object>> payables = aged(repository.findOpenPayables(companyId), today);
		return Map.of("receivables", receivables, "payables", payables, "totalReceivable", total(receivables),
				"totalPayable", total(payables));
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
