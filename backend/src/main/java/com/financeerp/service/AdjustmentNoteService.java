package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.AdjustmentNoteDto.LineRequest;
import com.financeerp.dto.AdjustmentNoteDto.NoteRequest;
import com.financeerp.repository.AdjustmentNoteRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Sales credit notes and purchase debit notes; each creates a DRAFT reversing journal. */
@Service
public class AdjustmentNoteService {
	private final AdjustmentNoteRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public AdjustmentNoteService(AdjustmentNoteRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	private record Totals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
			List<IndiaTax.Taxed> taxed, List<Long> accounts) {
		BigDecimal total() {
			return taxable.add(cgst).add(sgst).add(igst);
		}
	}

	@Transactional
	public Map<String, Object> creditNote(long companyId, long invoiceId, NoteRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		GstSupport.CompanyProfile company = gst.companyProfile(companyId);
		List<Map<String, Object>> rows = repository.lockSalesInvoice(companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> inv = rows.get(0);
		if (!"ISSUED".equals(inv.get("status")))
			throw new IllegalArgumentException("Credit notes can only be raised against issued invoices.");
		LocalDate invoiceDate = ((java.sql.Date) inv.get("date")).toLocalDate();
		requireNoteDate(req.noteDate(), invoiceDate);
		Totals t = compute(companyId, req, invoiceDate, "INTER_STATE".equals(inv.get("supply")),
				company.gstRegistered(), "INCOME", "SALES_RETURNS");
		requireWithinSource(companyId, true, invoiceId, (BigDecimal) inv.get("total"), t.total());
		poster.requireOpenPeriod(companyId, req.noteDate());
		String number = String.format("CN/%s/%05d", IndiaTax.fiscalYear(req.noteDate()).substring(2),
				gst.nextNumber(companyId, "CREDIT_NOTE", IndiaTax.fiscalYear(req.noteDate())));
		List<LedgerLine> lines = new ArrayList<>();
		accountTotals(t, true).forEach((acc, amt) -> lines.add(new LedgerLine(acc, number, amt, BigDecimal.ZERO)));
		debitTax(lines, companyId, "OUTPUT_CGST", t.cgst(), number);
		debitTax(lines, companyId, "OUTPUT_SGST", t.sgst(), number);
		debitTax(lines, companyId, "OUTPUT_IGST", t.igst(), number);
		lines.add(new LedgerLine(poster.systemAccount(companyId, "AR"), number, BigDecimal.ZERO, t.total()));
		return save(companyId, actor, "SALES_CREDIT", invoiceId, number, req, t, (String) inv.get("supply"), lines);
	}

	@Transactional
	public Map<String, Object> debitNote(long companyId, long billId, NoteRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		if (req.vendorNoteNumber() == null || req.vendorNoteNumber().isBlank() || req.vendorNoteNumber().length() > 50)
			throw new IllegalArgumentException("Vendor credit note number is required (maximum 50 characters).");
		List<Map<String, Object>> rows = repository.lockPurchaseBill(companyId, billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		Map<String, Object> bill = rows.get(0);
		if ((Boolean) bill.get("rcm"))
			throw new IllegalArgumentException(
					"Debit notes against reverse-charge bills are not supported yet; reverse the bill's journal and re-enter it.");
		if (!"BOOKED".equals(bill.get("status")))
			throw new IllegalArgumentException("Debit notes can only be raised against booked bills.");
		LocalDate billDate = ((java.sql.Date) bill.get("date")).toLocalDate();
		requireNoteDate(req.noteDate(), billDate);
		Totals t = compute(companyId, req, billDate, "INTER_STATE".equals(bill.get("supply")), bill.get("gstin") != null,
				"EXPENSE", "PURCHASES");
		requireWithinSource(companyId, false, billId, (BigDecimal) bill.get("total"), t.total());
		poster.requireOpenPeriod(companyId, req.noteDate());
		boolean itc = (Boolean) bill.get("itc");
		String number = req.vendorNoteNumber().trim();
		List<LedgerLine> lines = new ArrayList<>();
		lines.add(new LedgerLine(poster.systemAccount(companyId, "AP"), number, t.total(), BigDecimal.ZERO));
		// Ineligible GST was costed on the bill, so its reversal goes back to expense.
		accountTotals(t, itc).forEach((acc, amt) -> lines.add(new LedgerLine(acc, number, BigDecimal.ZERO, amt)));
		if (itc) {
			creditTax(lines, companyId, "INPUT_CGST", t.cgst(), number);
			creditTax(lines, companyId, "INPUT_SGST", t.sgst(), number);
			creditTax(lines, companyId, "INPUT_IGST", t.igst(), number);
		}
		return save(companyId, actor, "PURCHASE_DEBIT", billId, number, req, t, (String) bill.get("supply"), lines);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return repository.findNotes(companyId);
	}

	private void requireNoteDate(LocalDate noteDate, LocalDate sourceDate) {
		if (noteDate.isBefore(sourceDate))
			throw new IllegalArgumentException("Note date cannot be before the original document date.");
	}

	private Totals compute(long companyId, NoteRequest req, LocalDate rateDate, boolean inter, boolean taxApplicable,
			String accountType, String defaultKey) {
		BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
		List<IndiaTax.Taxed> taxed = new ArrayList<>();
		List<Long> accounts = new ArrayList<>();
		long defaultAccount = -1;
		for (LineRequest l : req.lines()) {
			gst.checkLine(l.quantity(), l.unitPrice(), l.gstRate(), l.hsnSac(), rateDate, taxApplicable);
			long account;
			if (l.accountId() != null) {
				gst.requireAccount(companyId, l.accountId(), accountType);
				account = l.accountId();
			} else {
				if (defaultAccount < 0)
					defaultAccount = poster.systemAccount(companyId, defaultKey);
				account = defaultAccount;
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
		return new Totals(taxable, cgst, sgst, igst, taxed, accounts);
	}

	private void requireWithinSource(long companyId, boolean salesInvoice, long sourceId, BigDecimal sourceTotal,
			BigDecimal noteTotal) {
		BigDecimal prior = repository.sumNoteTotals(companyId, salesInvoice, sourceId);
		if (prior.add(noteTotal).compareTo(sourceTotal) > 0)
			throw new IllegalArgumentException("Total notes (" + prior.add(noteTotal).toPlainString()
					+ ") would exceed the original document total (" + sourceTotal.toPlainString() + ").");
	}

	/** Per-account amount; includeTax=false folds the GST into each line's account. */
	private Map<Long, BigDecimal> accountTotals(Totals t, boolean taxSeparate) {
		Map<Long, BigDecimal> sums = new TreeMap<>();
		for (int i = 0; i < t.taxed().size(); i++) {
			IndiaTax.Taxed x = t.taxed().get(i);
			sums.merge(t.accounts().get(i), taxSeparate ? x.taxable() : x.total(), BigDecimal::add);
		}
		return sums;
	}

	private void debitTax(List<LedgerLine> lines, long companyId, String key, BigDecimal amount, String ref) {
		if (amount.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, key), ref + " " + key, amount,
					BigDecimal.ZERO));
	}

	private void creditTax(List<LedgerLine> lines, long companyId, String key, BigDecimal amount, String ref) {
		if (amount.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, key), ref + " " + key, BigDecimal.ZERO,
					amount));
	}

	private Map<String, Object> save(long companyId, long actor, String type, long sourceId, String number,
			NoteRequest req, Totals t, String supply, List<LedgerLine> lines) {
		boolean sales = "SALES_CREDIT".equals(type);
		long journalId = poster.createDraftJournal(companyId, req.noteDate(),
				(sales ? "Credit note " : "Debit note ") + number, type + ":" + sourceId + ":" + number, actor, lines);
		Long id = repository.insertNote(companyId, sales, type, sourceId, number, req.noteDate(),
				req.reason().trim(), supply, t.taxable(), t.cgst(), t.sgst(), t.igst(), t.total(), journalId, actor);
		for (int i = 0; i < req.lines().size(); i++) {
			LineRequest l = req.lines().get(i);
			IndiaTax.Taxed x = t.taxed().get(i);
			repository.insertNoteLine(companyId, id, l.description().trim(), l.hsnSac(), l.quantity(), l.unitPrice(),
					l.gstRate(), x.taxable(), x.cgst(), x.sgst(), x.igst(), t.accounts().get(i));
		}
		poster.audit(companyId, sales ? "CREDIT_NOTE_CREATED" : "DEBIT_NOTE_CREATED", "ADJUSTMENT_NOTE", id, number);
		return list(companyId).stream().filter(n -> ((Number) n.get("id")).longValue() == id).findFirst().orElseThrow();
	}
}
