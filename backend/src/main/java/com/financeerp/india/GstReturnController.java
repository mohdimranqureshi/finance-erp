package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** GSTR-1 style working data from posted, non-reversed documents. This is not a filing-format file. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/gst")
public class GstReturnController {
	private static final BigDecimal B2C_LARGE_LIMIT = new BigDecimal("250000");
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;

	public GstReturnController(JdbcTemplate jdbc, CompanyAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@GetMapping("/gstr1")
	public Map<String, Object> gstr1(@PathVariable long companyId, @RequestParam LocalDate from,
			@RequestParam LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT i.id,i.invoice_number AS num,i.invoice_date AS dt,i.place_of_supply AS pos,i.total_amount AS val,
				       i.supply_type AS supply,p.gstin AS ctin,l.gst_rate AS rt,l.hsn_sac AS hsn,l.quantity AS qty,
				       l.taxable_amount AS txval,l.cgst_amount AS camt,l.sgst_amount AS samt,l.igst_amount AS iamt
				FROM sales_invoices i
				JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				JOIN sales_invoice_lines l ON l.company_id=i.company_id AND l.invoice_id=i.id
				JOIN journal_entries j ON j.company_id=i.company_id AND j.id=i.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE i.company_id=? AND i.invoice_date BETWEEN ? AND ? AND (r.id IS NULL OR r.status<>'POSTED')
				ORDER BY i.invoice_number,l.id
				""", companyId, from, to);

		Map<String, Map<String, Object>> b2b = new LinkedHashMap<>(), b2cl = new LinkedHashMap<>();
		Map<String, Map<String, Object>> b2cs = new LinkedHashMap<>(), hsn = new LinkedHashMap<>();
		Map<String, Map<String, Object>> invoices = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			String key = String.valueOf(r.get("id"));
			boolean registered = r.get("ctin") != null;
			BigDecimal val = (BigDecimal) r.get("val");
			boolean large = !registered && "INTER_STATE".equals(r.get("supply")) && val.compareTo(B2C_LARGE_LIMIT) > 0;
			if (registered || large) {
				Map<String, Object> inv = invoices.computeIfAbsent(key, k -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("inum", r.get("num"));
					m.put("idt", r.get("dt"));
					m.put("val", val);
					m.put("pos", r.get("pos"));
					m.put("rchrg", "N");
					m.put("ctin", r.get("ctin"));
					m.put("section", registered ? "b2b" : "b2cl");
					m.put("items", new LinkedHashMap<String, Map<String, Object>>());
					return m;
				});
				@SuppressWarnings("unchecked")
				Map<String, Map<String, Object>> items = (Map<String, Map<String, Object>>) inv.get("items");
				addTax(items.computeIfAbsent(String.valueOf(r.get("rt")), k -> newBucket("rt", r.get("rt"))), r);
			} else {
				Map<String, Object> bucket = b2cs.computeIfAbsent(r.get("pos") + "|" + r.get("rt") + "|" + r.get("supply"),
						k -> {
							Map<String, Object> m = newBucket("rt", r.get("rt"));
							m.put("pos", r.get("pos"));
							m.put("sply_ty", r.get("supply"));
							return m;
						});
				addTax(bucket, r);
			}
			Map<String, Object> h = hsn.computeIfAbsent(r.get("hsn") + "|" + r.get("rt"), k -> {
				Map<String, Object> m = newBucket("rt", r.get("rt"));
				m.put("hsn_sc", r.get("hsn"));
				m.put("qty", BigDecimal.ZERO);
				return m;
			});
			addTax(h, r);
			h.put("qty", ((BigDecimal) h.get("qty")).add((BigDecimal) r.get("qty")));
		}
		List<Map<String, Object>> b2bList = new ArrayList<>(), b2clList = new ArrayList<>();
		for (Map<String, Object> inv : invoices.values()) {
			Map<String, Object> out = new LinkedHashMap<>(inv);
			out.put("items", new ArrayList<Object>(((Map<?, ?>) inv.get("items")).values()));
			("b2b".equals(inv.get("section")) ? b2bList : b2clList).add(out);
		}

		List<Map<String, Object>> notes = jdbc.queryForList("""
				SELECT n.note_number AS nt_num,n.note_date AS nt_dt,i.invoice_number AS inum,p.gstin AS ctin,
				       n.total_amount AS val,n.taxable_amount AS txval,n.cgst_amount AS camt,n.sgst_amount AS samt,
				       n.igst_amount AS iamt,i.place_of_supply AS pos
				FROM adjustment_notes n
				JOIN sales_invoices i ON i.company_id=n.company_id AND i.id=n.sales_invoice_id
				JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id
				JOIN journal_entries j ON j.company_id=n.company_id AND j.id=n.journal_entry_id AND j.status='POSTED'
				LEFT JOIN journal_entries r ON r.company_id=j.company_id AND r.id=j.reversed_by_journal_id
				WHERE n.company_id=? AND n.note_type='SALES_CREDIT' AND n.note_date BETWEEN ? AND ?
				  AND (r.id IS NULL OR r.status<>'POSTED')
				ORDER BY n.note_number
				""", companyId, from, to);
		List<Map<String, Object>> cdnr = new ArrayList<>(), cdnur = new ArrayList<>();
		for (Map<String, Object> n : notes) {
			Map<String, Object> m = new LinkedHashMap<>(n);
			m.put("ntty", "C");
			(n.get("ctin") != null ? cdnr : cdnur).add(m);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("b2b", b2bList);
		out.put("b2cl", b2clList);
		out.put("b2cs", new ArrayList<>(b2cs.values()));
		out.put("cdnr", cdnr);
		out.put("cdnur", cdnur);
		out.put("hsn", new ArrayList<>(hsn.values()));
		out.put("note", "Working data only: not in the GST portal upload format. Exports, SEZ, nil-rated/exempt, advances, amendments and reverse-charge supplies are not covered. File through the GST portal or a GSP after review.");
		return out;
	}

	private static Map<String, Object> newBucket(String rateKey, Object rate) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(rateKey, rate);
		m.put("txval", BigDecimal.ZERO);
		m.put("camt", BigDecimal.ZERO);
		m.put("samt", BigDecimal.ZERO);
		m.put("iamt", BigDecimal.ZERO);
		return m;
	}

	private static void addTax(Map<String, Object> bucket, Map<String, Object> row) {
		for (String k : List.of("txval", "camt", "samt", "iamt"))
			bucket.put(k, ((BigDecimal) bucket.get(k)).add((BigDecimal) row.get(k)));
	}
}
