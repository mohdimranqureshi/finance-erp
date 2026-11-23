package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class Gstr3bRepository {
	private static final String POSTED = """
			JOIN journal_entries j ON j.company_id=%1$s.company_id AND j.id=%1$s.journal_entry_id AND j.status='POSTED'
			LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
			""";
	private static final String NOT_REVERSED = " AND (r.id IS NULL OR r.status<>'POSTED')";
	private static final String HEADS = """
			COALESCE(SUM(%1$s.taxable_amount),0) AS taxable,COALESCE(SUM(%1$s.igst_amount),0) AS igst,
			COALESCE(SUM(%1$s.cgst_amount),0) AS cgst,COALESCE(SUM(%1$s.sgst_amount),0) AS sgst""";

	private final JdbcTemplate jdbc;

	public Gstr3bRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<String, Object> outwardTaxable(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("SELECT " + HEADS.formatted("l") + """
				 FROM sales_invoices i JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				""" + POSTED.formatted("i") + " WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND l.gst_rate>0"
				+ NOT_REVERSED, companyId, from, to);
	}

	public Map<String, Object> salesCreditNotes(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("SELECT " + HEADS.formatted("n") + " FROM adjustment_notes n "
				+ POSTED.formatted("n") + " WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?"
				+ NOT_REVERSED, companyId, from, to);
	}

	public BigDecimal nilRatedTaxable(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(l.taxable_amount),0) FROM sales_invoices i "
				+ "JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id " + POSTED.formatted("i")
				+ " WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND l.gst_rate=0" + NOT_REVERSED,
				BigDecimal.class, companyId, from, to);
	}

	public List<Map<String, Object>> unregisteredInterState(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForList("SELECT i.place_of_supply AS \"placeOfSupply\","
				+ HEADS.formatted("l") + """
				 FROM sales_invoices i JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				 JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				""" + POSTED.formatted("i") + """
				 WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND p.gstin IS NULL
				 AND i.supply_type='INTER_STATE' AND l.gst_rate>0""" + NOT_REVERSED + """
				 GROUP BY i.place_of_supply ORDER BY i.place_of_supply""", companyId, from, to);
	}

	public Map<String, Object> reverseChargeBills(long companyId, LocalDate from, LocalDate to) {
		return bills(companyId, from, to, "b.reverse_charge");
	}

	public Map<String, Object> reverseChargeItcBills(long companyId, LocalDate from, LocalDate to) {
		return bills(companyId, from, to, "b.reverse_charge AND b.itc_eligible");
	}

	public Map<String, Object> forwardChargeItcBills(long companyId, LocalDate from, LocalDate to) {
		return bills(companyId, from, to, "NOT b.reverse_charge AND b.itc_eligible");
	}

	public Map<String, Object> ineligibleItcBills(long companyId, LocalDate from, LocalDate to) {
		return bills(companyId, from, to, "NOT b.itc_eligible AND b.cgst_amount+b.sgst_amount+b.igst_amount>0");
	}

	public Map<String, Object> supplierCreditNotes(long companyId, LocalDate from, LocalDate to) {
		return jdbc.queryForMap("SELECT " + HEADS.formatted("n") + """
				 FROM adjustment_notes n
				 JOIN purchase_bills b ON b.company_id=n.company_id AND b.id=n.purchase_bill_id AND b.itc_eligible
				""" + POSTED.formatted("n") + " WHERE n.company_id=? AND n.note_type='PURCHASE_DEBIT' AND n.note_date BETWEEN ? AND ?"
				+ NOT_REVERSED, companyId, from, to);
	}

	private Map<String, Object> bills(long companyId, LocalDate from, LocalDate to, String condition) {
		return jdbc.queryForMap("SELECT " + HEADS.formatted("b") + " FROM purchase_bills b " + POSTED.formatted("b")
				+ " WHERE b.company_id=? AND b.bill_date BETWEEN ? AND ? AND " + condition + NOT_REVERSED, companyId, from, to);
	}

	public Integer countGstr2bImports(long companyId, String period) {
		return jdbc.queryForObject("SELECT count(*) FROM gstr2b_imports WHERE company_id=? AND return_period=?",
				Integer.class, companyId, period);
	}

	/** Forward-charge ITC available in the month's GSTR-2B; credit notes reduce it. */
	public Map<String, Object> gstr2bAvailableItc(long companyId, String period) {
		return jdbc.queryForMap("""
				SELECT COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.taxable_amount ELSE l.taxable_amount END),0) AS taxable,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.igst_amount ELSE l.igst_amount END),0) AS igst,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.cgst_amount ELSE l.cgst_amount END),0) AS cgst,
				 COALESCE(SUM(CASE WHEN l.doc_type='CREDIT_NOTE' THEN -l.sgst_amount ELSE l.sgst_amount END),0) AS sgst
				FROM gstr2b_imports i
				JOIN gstr2b_lines l ON l.company_id=i.company_id AND l.import_id=i.id
				WHERE i.company_id=? AND i.return_period=? AND l.itc_available AND NOT l.reverse_charge
				""", companyId, period);
	}
}
