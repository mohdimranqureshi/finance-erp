package com.financeerp.service;

import com.financeerp.domain.IndiaTax;
import com.financeerp.domain.LedgerLine;
import com.financeerp.dto.SalesInvoiceDto.CreateRequest;
import com.financeerp.dto.SalesInvoiceDto.LineRequest;
import com.financeerp.repository.SalesInvoiceRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class SalesInvoiceService {
	private final SalesInvoiceRepository repository;
	private final CompanyAccess access;
	private final LedgerPoster poster;
	private final GstSupport gst;

	public SalesInvoiceService(SalesInvoiceRepository repository, CompanyAccess access, LedgerPoster poster,
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
		Map<String, Object> customer = gst.party(companyId, req.customerId(), "CUSTOMER");
		String pos = req.placeOfSupply() == null || req.placeOfSupply().isBlank() ? ((String) customer.get("stateCode")).trim()
				: req.placeOfSupply().trim();
		if (!IndiaTax.STATES.containsKey(pos))
			throw new IllegalArgumentException("Unknown place of supply state code.");
		if (req.dueDate() != null && req.dueDate().isBefore(req.invoiceDate()))
			throw new IllegalArgumentException("Due date cannot be before the invoice date.");
		String tcsSection = req.tcsSection() == null || req.tcsSection().isBlank() ? null : req.tcsSection().trim();
		if (tcsSection != null && !IndiaTax.isTcsSection(tcsSection))
			throw new IllegalArgumentException("Unknown TCS section. See /api/v1/india/reference for supported codes.");
		if ((tcsSection == null) != (req.tcsRate() == null))
			throw new IllegalArgumentException("Provide the TCS section together with the TCS rate.");
		if (req.tcsRate() != null && (req.tcsRate().signum() <= 0 || req.tcsRate().compareTo(BigDecimal.valueOf(20)) > 0
				|| req.tcsRate().scale() > 3))
			throw new IllegalArgumentException("TCS rate must be above 0 and at most 20, with at most three decimals.");
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
		BigDecimal gstValue = taxable.add(cgst).add(sgst).add(igst);
		if (gstValue.signum() <= 0)
			throw new IllegalArgumentException("Invoice total must be greater than zero.");
		BigDecimal tcs = tcsSection == null ? BigDecimal.ZERO.setScale(2) : IndiaTax.tcs(gstValue, req.tcsRate());
		BigDecimal total = gstValue.add(tcs);
		Long id = repository.insertSalesInvoice(companyId, req.customerId(), req.invoiceDate(), req.dueDate(), pos,
				inter ? "INTER_STATE" : "INTRA_STATE", taxable, cgst, sgst, igst, total, actor, tcsSection,
				req.tcsRate(), tcs);
		for (int i = 0; i < req.lines().size(); i++) {
			LineRequest l = req.lines().get(i);
			IndiaTax.Taxed t = taxed.get(i);
			repository.insertSalesInvoiceLine(companyId, id, l.description().trim(), l.hsnSac(), l.quantity(),
					l.unitPrice(), l.gstRate(), t.taxable(), t.cgst(), t.sgst(), t.igst(), accounts.get(i));
		}
		poster.audit(companyId, "SALES_INVOICE_CREATED", "SALES_INVOICE", id, "DRAFT");
		return get(companyId, id);
	}

	public List<Map<String, Object>> list(long companyId) {
		access.requireMembership(companyId);
		return repository.findInvoices(companyId);
	}

	public Map<String, Object> get(long companyId, long invoiceId) {
		access.requireMembership(companyId);
		List<Map<String, Object>> rows = repository.findInvoice(companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
		out.put("lines", repository.findInvoiceLines(companyId, invoiceId));
		return out;
	}

	/** Assigns the gapless invoice number and creates a DRAFT journal; the journal still needs independent approval. */
	@Transactional
	public Map<String, Object> issue(long companyId, long invoiceId) {
		long actor = access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		List<Map<String, Object>> rows = repository.lockInvoiceForIssue(companyId, invoiceId);
		if (rows.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		Map<String, Object> inv = rows.get(0);
		if (!"DRAFT".equals(inv.get("status")))
			throw new IllegalArgumentException("Only draft invoices can be issued.");
		LocalDate date = ((java.sql.Date) inv.get("invoiceDate")).toLocalDate();
		poster.requireOpenPeriod(companyId, date);
		int seq = gst.nextNumber(companyId, "SALES_INVOICE", IndiaTax.fiscalYear(date));
		String number = String.format("INV/%s/%05d", IndiaTax.fiscalYear(date).substring(2), seq);
		List<LedgerLine> lines = new ArrayList<>();
		lines.add(new LedgerLine(poster.systemAccount(companyId, "AR"), number, (BigDecimal) inv.get("total"),
				BigDecimal.ZERO));
		for (SalesInvoiceRepository.RevenueTotal row : repository.findRevenueTotals(companyId, invoiceId))
			lines.add(new LedgerLine(row.accountId(), number + " revenue", BigDecimal.ZERO, row.taxable()));
		addTax(lines, companyId, "OUTPUT_CGST", (BigDecimal) inv.get("cgst"), number);
		addTax(lines, companyId, "OUTPUT_SGST", (BigDecimal) inv.get("sgst"), number);
		addTax(lines, companyId, "OUTPUT_IGST", (BigDecimal) inv.get("igst"), number);
		addTax(lines, companyId, "TCS_PAYABLE", (BigDecimal) inv.get("tcs"), number);
		long journalId = poster.createDraftJournal(companyId, date, "Sales invoice " + number,
				"SALES_INVOICE:" + invoiceId, actor, lines);
		repository.markIssued(companyId, invoiceId, number, journalId);
		poster.audit(companyId, "SALES_INVOICE_ISSUED", "SALES_INVOICE", invoiceId, number);
		return get(companyId, invoiceId);
	}

	private void addTax(List<LedgerLine> lines, long companyId, String key, BigDecimal amount, String number) {
		if (amount.signum() > 0)
			lines.add(new LedgerLine(poster.systemAccount(companyId, key), number + " " + key, BigDecimal.ZERO,
					amount));
	}
}
