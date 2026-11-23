package com.financeerp.service;

import com.financeerp.repository.GstReturnRepository;
import com.financeerp.security.CompanyAccess;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** GSTR-1 style working data from posted, non-reversed documents. This is not a filing-format file. */
@Service
public class GstReturnService {
	private static final BigDecimal B2C_LARGE_LIMIT = new BigDecimal("250000");
	private final GstReturnRepository repository;
	private final CompanyAccess access;

	public GstReturnService(GstReturnRepository repository, CompanyAccess access) {
		this.repository = repository;
		this.access = access;
	}

	public Map<String, Object> gstr1(long companyId, LocalDate from, LocalDate to) {
		access.requireMembership(companyId);
		if (from.isAfter(to))
			throw new IllegalArgumentException("'from' must be on or before 'to'.");
		List<Map<String, Object>> rows = repository.findOutwardInvoiceLines(companyId, from, to);

		Map<String, Map<String, Object>> b2b = new LinkedHashMap<>(), b2cl = new LinkedHashMap<>();
		Map<String, Map<String, Object>> b2cs = new LinkedHashMap<>(), hsn = new LinkedHashMap<>();
		Map<String, Map<String, Object>> invoices = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			String key = String.valueOf(r.get("id"));
			boolean registered = r.get("ctin") != null;
			BigDecimal val = (BigDecimal) r.get("val");
			boolean large = !registered && "INTER_STATE".equals(r.get("supply"))
					&& ((BigDecimal) r.get("gstval")).compareTo(B2C_LARGE_LIMIT) > 0;
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

		List<Map<String, Object>> notes = repository.findSalesCreditNotes(companyId, from, to);
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
