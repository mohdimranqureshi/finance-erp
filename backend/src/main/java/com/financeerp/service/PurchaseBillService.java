package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.PurchaseBillDto.CreateRequest;
import com.financeerp.dto.PurchaseBillDto.LineRequest;
import com.financeerp.repository.PurchaseBillRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class PurchaseBillService {
	private final PurchaseBillRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public PurchaseBillService(PurchaseBillRepository repository, CompanyAccess access, LedgerPoster poster,
			GstSupport gst) {
		this.repository = repository;
		this.access = access;
		this.poster = poster;
		this.gst = gst;
	}

	@Transactional
	public Map<String, Object> create(long companyId, CreateRequest req) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		GstSupport.CompanyProfile company = gst.companyProfile(companyId);
		Map<String, Object> vendor = gst.party(companyId, req.vendorId(), "VENDOR");
		boolean vendorRegistered = vendor.get("gstin") != null;
		boolean rcm = Boolean.TRUE.equals(req.reverseCharge());
		if (rcm && !company.gstRegistered())
			throw new IllegalArgumentException("Reverse charge applies only when the company is GST-registered.");
		// Under RCM the company, not the supplier, is liable for the tax, so rates are allowed for unregistered suppliers.
		boolean taxApplicable = rcm || vendorRegistered;
		if (req.dueDate() != null && req.dueDate().isBefore(req.billDate()))
			throw new IllegalArgumentException("Due date cannot be before the bill date.");
		boolean inter = !company.stateCode().equals(((String) vendor.get("stateCode")).trim());
		boolean itc = company.gstRegistered() && (req.itcEligible() == null || req.itcEligible());
		if (req.tdsSection() != null && (req.tdsRate() == null || req.tdsRate().signum() <= 0
				|| req.tdsRate().compareTo(BigDecimal.valueOf(30)) > 0 || req.tdsRate().scale() > 2))
			throw new IllegalArgumentException("TDS rate must be above 0 and at most 30, with at most two decimals.");
		if (req.tdsSection() == null && req.tdsRate() != null)
			throw new IllegalArgumentException("Provide a TDS section together with the TDS rate.");
		if (repository.countByVendorInvoiceNumber(companyId, req.vendorId(), req.vendorInvoiceNumber().trim()) > 0)
			throw new IllegalArgumentException("This vendor invoice number has already been recorded (possible duplicate bill).");
		long defaultExpense = -1;
		List<IndiaTax.Taxed> taxed = new ArrayList<>();
		List<Long> accounts = new ArrayList<>();
		BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
		for (LineRequest l : req.lines()) {
			gst.checkLine(l.quantity(), l.unitPrice(), l.gstRate(), l.hsnSac(), req.billDate(), taxApplicable);
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
		BigDecimal tax = cgst.add(sgst).add(igst);
		if (rcm && tax.signum() == 0)
			throw new IllegalArgumentException("A reverse-charge bill needs the applicable GST rate on its lines.");
		BigDecimal total = rcm ? taxable : taxable.add(tax);
		BigDecimal tds = req.tdsSection() == null ? BigDecimal.ZERO.setScale(2) : IndiaTax.tds(taxable, req.tdsRate());
		if (total.signum() <= 0)
			throw new IllegalArgumentException("Bill total must be greater than zero.");
		Long id = repository.insertPurchaseBill(companyId, req.vendorId(), req.vendorInvoiceNumber().trim(),
				req.billDate(), req.dueDate(), company.stateCode(), inter ? "INTER_STATE" : "INTRA_STATE", taxable,
				cgst, sgst, igst, total, itc, req.tdsSection(), req.tdsRate(), tds, actor, rcm);
		for (int i = 0; i < req.lines().size(); i++) {
			LineRequest l = req.lines().get(i);
			IndiaTax.Taxed t = taxed.get(i);
			repository.insertPurchaseBillLine(companyId, id, l.description().trim(), l.hsnSac(), l.quantity(),
					l.unitPrice(), l.gstRate(), t.taxable(), t.cgst(), t.sgst(), t.igst(), accounts.get(i));
		}
		poster.audit(companyId, "PURCHASE_BILL_CREATED", "PURCHASE_BILL", id, "DRAFT");
		return get(companyId, id);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return repository.findBills(companyId);
	}

	public Map<String, Object> get(long companyId, long billId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findBill(companyId, billId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Bill not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", repository.findBillLines(companyId, billId));
		return out;
	}

	/** Creates a DRAFT journal; the journal still needs independent approval before it reaches the ledger. */
	@Transactional
	public Map<String, Object> book(long companyId, long billId) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = repository.lockBillForBooking(companyId, billId);
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
		List<LedgerLine> lines = new ArrayList<>();
		// Without eligible ITC, the GST is a cost and is added to each line's expense.
		for (PurchaseBillRepository.AccountTotal row : repository.findAccountTotals(companyId, billId)) {
			BigDecimal amount = itc ? row.taxable() : row.taxable().add(row.tax());
			lines.add(new LedgerLine(row.accountId(), ref, amount, BigDecimal.ZERO));
		}
		if (itc) {
			addInput(lines, companyId, "INPUT_CGST", cgst, ref);
			addInput(lines, companyId, "INPUT_SGST", sgst, ref);
			addInput(lines, companyId, "INPUT_IGST", igst, ref);
		}
		// Reverse charge: the company owes the GST to the government (paid in cash), not to the supplier.
		if ((Boolean) bill.get("rcm")) {
			addRcmLiability(lines, companyId, "RCM_CGST", cgst, ref);
			addRcmLiability(lines, companyId, "RCM_SGST", sgst, ref);
			addRcmLiability(lines, companyId, "RCM_IGST", igst, ref);
		}
		if (tds.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, "TDS_PAYABLE"), ref + " TDS",
					BigDecimal.ZERO, tds));
		lines.add(new LedgerLine(poster.systemAccount(companyId, "AP"), ref, BigDecimal.ZERO,
				total.subtract(tds)));
		long journalId = poster.createDraftJournal(companyId, date, ref, "PURCHASE_BILL:" + billId, actor, lines);
		repository.markBooked(companyId, billId, journalId);
		poster.audit(companyId, "PURCHASE_BILL_BOOKED", "PURCHASE_BILL", billId, String.valueOf(bill.get("ref")));
		return get(companyId, billId);
	}

	private void addRcmLiability(List<LedgerLine> lines, long companyId, String key, BigDecimal amount, String ref) {
		if (amount.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, key), ref + " " + key, BigDecimal.ZERO,
					amount));
	}

	private void addInput(List<LedgerLine> lines, long companyId, String key, BigDecimal amount, String ref) {
		if (amount.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, key), ref + " " + key, amount,
					BigDecimal.ZERO));
	}
}
