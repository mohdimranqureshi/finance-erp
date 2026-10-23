package com.financeerp.india;

import com.financeerp.security.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds e-invoice (IRP) and e-way bill JSON payloads. Nothing is sent to the government portals: the IRN and
 * e-way bill number must be obtained through the portal or a GSP and recorded here.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/sales-invoices/{invoiceId}")
public class EInvoiceController {
	private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final BigDecimal EWAY_LIMIT = new BigDecimal("50000");
	private final JdbcTemplate jdbc;
	private final CompanyAccess access;
	private final LedgerPoster poster;

	public EInvoiceController(JdbcTemplate jdbc, CompanyAccess access, LedgerPoster poster) {
		this.jdbc = jdbc;
		this.access = access;
		this.poster = poster;
	}

	public record IrnRequest(@NotNull @Pattern(regexp = "[0-9a-f]{64}") String irn) {
	}

	public record TransportRequest(@Min(1) @Max(4) int transMode, @Min(0) @Max(4000) int transDistance,
			@Pattern(regexp = "([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z])?") String transporterId,
			@Pattern(regexp = "([A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4})?") String vehicleNo,
			@Pattern(regexp = "[RO]") String vehicleType) {
	}

	private record Source(Map<String, Object> invoice, Map<String, Object> seller, Map<String, Object> buyer,
			List<Map<String, Object>> lines) {
	}

	private Source load(long companyId, long invoiceId) {
		List<Map<String, Object>> inv = jdbc.queryForList(
				"SELECT id,invoice_number AS num,invoice_date AS dt,place_of_supply AS pos,supply_type AS supply,total_amount AS total,taxable_amount AS taxable,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst,customer_id AS customer,status,irn FROM sales_invoices WHERE company_id=? AND id=?",
				companyId, invoiceId);
		if (inv.isEmpty())
			throw new IllegalArgumentException("Invoice not found.");
		if (!"ISSUED".equals(inv.get(0).get("status")))
			throw new IllegalArgumentException("Only issued invoices can be used.");
		Map<String, Object> seller = jdbc.queryForMap(
				"SELECT gstin,COALESCE(legal_name,name) AS name,address_line AS addr,city,pincode,state_code AS state FROM companies WHERE id=?",
				companyId);
		Map<String, Object> buyer = jdbc.queryForMap(
				"SELECT gstin,name,address_line AS addr,city,pincode,state_code AS state FROM parties WHERE company_id=? AND id=?",
				companyId, inv.get(0).get("customer"));
		List<Map<String, Object>> lines = jdbc.queryForList(
				"SELECT description,hsn_sac AS hsn,quantity AS qty,unit_price AS price,gst_rate AS rate,taxable_amount AS taxable,cgst_amount AS cgst,sgst_amount AS sgst,igst_amount AS igst FROM sales_invoice_lines WHERE company_id=? AND invoice_id=? ORDER BY id",
				companyId, invoiceId);
		return new Source(inv.get(0), seller, buyer, lines);
	}

	private void requireParty(Map<String, Object> party, String label, boolean needGstin) {
		List<String> missing = new ArrayList<>();
		if (needGstin && party.get("gstin") == null)
			missing.add("GSTIN");
		for (String f : List.of("addr", "city", "pincode"))
			if (party.get(f) == null)
				missing.add(f.equals("addr") ? "address" : f);
		if (!missing.isEmpty())
			throw new IllegalArgumentException(label + " is missing: " + String.join(", ", missing) + ".");
	}

	@GetMapping("/e-invoice-payload")
	public Map<String, Object> eInvoice(@PathVariable long companyId, @PathVariable long invoiceId) {
		access.requireMembership(companyId);
		Source s = load(companyId, invoiceId);
		requireParty(s.seller(), "Company profile", true);
		requireParty(s.buyer(), "Customer", true);
		Map<String, Object> inv = s.invoice();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("Version", "1.1");
		out.put("TranDtls", Map.of("TaxSch", "GST", "SupTyp", "B2B", "RegRev", "N", "IgstOnIntra", "N"));
		out.put("DocDtls", Map.of("Typ", "INV", "No", inv.get("num"), "Dt", date(inv.get("dt"))));
		out.put("SellerDtls", party(s.seller(), null));
		out.put("BuyerDtls", party(s.buyer(), (String) inv.get("pos")));
		List<Map<String, Object>> items = new ArrayList<>();
		int n = 1;
		for (Map<String, Object> l : s.lines()) {
			BigDecimal tax = ((BigDecimal) l.get("cgst")).add((BigDecimal) l.get("sgst")).add((BigDecimal) l.get("igst"));
			Map<String, Object> it = new LinkedHashMap<>();
			it.put("SlNo", String.valueOf(n++));
			it.put("PrdDesc", l.get("description"));
			it.put("IsServc", String.valueOf(l.get("hsn")).startsWith("99") ? "Y" : "N");
			it.put("HsnCd", l.get("hsn"));
			it.put("Qty", l.get("qty"));
			it.put("Unit", "OTH");
			it.put("UnitPrice", l.get("price"));
			it.put("TotAmt", l.get("taxable"));
			it.put("AssAmt", l.get("taxable"));
			it.put("GstRt", l.get("rate"));
			it.put("IgstAmt", l.get("igst"));
			it.put("CgstAmt", l.get("cgst"));
			it.put("SgstAmt", l.get("sgst"));
			it.put("TotItemVal", ((BigDecimal) l.get("taxable")).add(tax));
			items.add(it);
		}
		out.put("ItemList", items);
		out.put("ValDtls", Map.of("AssVal", inv.get("taxable"), "CgstVal", inv.get("cgst"), "SgstVal", inv.get("sgst"),
				"IgstVal", inv.get("igst"), "TotInvVal", inv.get("total")));
		out.put("irn", inv.get("irn"));
		out.put("note", "Payload only. Submit via the Invoice Registration Portal or a GSP, then record the IRN. Applicability depends on aggregate turnover and is not checked here.");
		return out;
	}

	@PostMapping("/irn")
	@Transactional
	public Map<String, Object> recordIrn(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody IrnRequest req) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		int n = jdbc.update(
				"UPDATE sales_invoices SET irn=?,irn_recorded_at=now() WHERE company_id=? AND id=? AND status='ISSUED' AND irn IS NULL",
				req.irn(), companyId, invoiceId);
		if (n == 0)
			throw new IllegalArgumentException("Invoice not found, not issued, or an IRN is already recorded.");
		poster.audit(companyId, "IRN_RECORDED", "SALES_INVOICE", invoiceId, req.irn());
		return Map.of("invoiceId", invoiceId, "irn", req.irn());
	}

	@PostMapping("/e-way-bill-payload")
	public Map<String, Object> eWayBill(@PathVariable long companyId, @PathVariable long invoiceId,
			@Valid @RequestBody TransportRequest t) {
		access.requireMembership(companyId);
		Source s = load(companyId, invoiceId);
		if (s.lines().stream().allMatch(l -> String.valueOf(l.get("hsn")).startsWith("99")))
			throw new IllegalArgumentException("E-way bills apply to movement of goods, not services.");
		if (t.transMode() == 1 && t.vehicleNo() == null && (t.transporterId() == null || t.transporterId().isBlank()))
			throw new IllegalArgumentException("Provide a vehicle number or a transporter GSTIN for road transport.");
		requireParty(s.seller(), "Company profile", true);
		requireParty(s.buyer(), "Customer", false);
		Map<String, Object> inv = s.invoice();
		boolean inter = "INTER_STATE".equals(inv.get("supply"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("required", ((BigDecimal) inv.get("total")).compareTo(EWAY_LIMIT) > 0);
		out.put("supplyType", "O");
		out.put("subSupplyType", "1");
		out.put("docType", "INV");
		out.put("docNo", inv.get("num"));
		out.put("docDate", date(inv.get("dt")));
		out.put("fromGstin", s.seller().get("gstin"));
		out.put("fromTrdName", s.seller().get("name"));
		out.put("fromAddr1", s.seller().get("addr"));
		out.put("fromPlace", s.seller().get("city"));
		out.put("fromPincode", Integer.valueOf((String) s.seller().get("pincode")));
		out.put("fromStateCode", Integer.valueOf(((String) s.seller().get("state")).trim()));
		out.put("actFromStateCode", Integer.valueOf(((String) s.seller().get("state")).trim()));
		out.put("toGstin", s.buyer().get("gstin") == null ? "URP" : s.buyer().get("gstin"));
		out.put("toTrdName", s.buyer().get("name"));
		out.put("toAddr1", s.buyer().get("addr"));
		out.put("toPlace", s.buyer().get("city"));
		out.put("toPincode", Integer.valueOf((String) s.buyer().get("pincode")));
		out.put("toStateCode", Integer.valueOf(((String) s.buyer().get("state")).trim()));
		out.put("actToStateCode", Integer.valueOf(((String) inv.get("pos")).trim()));
		out.put("transactionType", 1);
		out.put("totalValue", inv.get("taxable"));
		out.put("cgstValue", inv.get("cgst"));
		out.put("sgstValue", inv.get("sgst"));
		out.put("igstValue", inv.get("igst"));
		out.put("cessValue", 0);
		out.put("totInvValue", inv.get("total"));
		out.put("transMode", t.transMode());
		out.put("transDistance", t.transDistance());
		out.put("transporterId", t.transporterId() == null || t.transporterId().isBlank() ? null : t.transporterId());
		out.put("vehicleNo", t.vehicleNo());
		out.put("vehicleType", t.vehicleType() == null ? "R" : t.vehicleType());
		List<Map<String, Object>> items = new ArrayList<>();
		for (Map<String, Object> l : s.lines()) {
			if (String.valueOf(l.get("hsn")).startsWith("99"))
				continue;
			BigDecimal rate = (BigDecimal) l.get("rate");
			BigDecimal half = rate.divide(BigDecimal.valueOf(2));
			Map<String, Object> it = new LinkedHashMap<>();
			it.put("productName", l.get("description"));
			it.put("productDesc", l.get("description"));
			it.put("hsnCode", Integer.valueOf(String.valueOf(l.get("hsn"))));
			it.put("quantity", l.get("qty"));
			it.put("qtyUnit", "OTH");
			it.put("taxableAmount", l.get("taxable"));
			it.put("sgstRate", inter ? BigDecimal.ZERO : half);
			it.put("cgstRate", inter ? BigDecimal.ZERO : half);
			it.put("igstRate", inter ? rate : BigDecimal.ZERO);
			it.put("cessRate", 0);
			items.add(it);
		}
		out.put("itemList", items);
		out.put("note", "Payload only. Generate the e-way bill on the e-way bill portal or via a GSP. The Rs 50,000 threshold and exemptions depend on the goods and state rules.");
		return out;
	}

	private Map<String, Object> party(Map<String, Object> p, String pos) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("Gstin", p.get("gstin"));
		m.put("LglNm", p.get("name"));
		if (pos != null)
			m.put("Pos", pos.trim());
		m.put("Addr1", p.get("addr"));
		m.put("Loc", p.get("city"));
		m.put("Pin", Integer.valueOf((String) p.get("pincode")));
		m.put("Stcd", ((String) p.get("state")).trim());
		return m;
	}

	private String date(Object sqlDate) {
		return ((java.sql.Date) sqlDate).toLocalDate().format(DMY);
	}
}
