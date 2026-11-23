package com.financeerp.repository;

import static com.financeerp.repository.OpeningItemSql.ITEM_JOIN;
import static com.financeerp.repository.OpeningItemSql.ITEM_OUTSTANDING;
import static com.financeerp.repository.OpeningItemSql.ITEM_SETTLEABLE;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class SettlementRepository {
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
	private static final String PAYMENT_SELECT = """
			SELECT p.id,p.direction,p.payment_date AS "paymentDate",p.amount,p.tds_amount AS "tdsAmount",
			 p.bank_account_id AS "bankAccountId",p.reference,p.sales_invoice_id AS "salesInvoiceId",
			 p.purchase_bill_id AS "purchaseBillId",p.opening_item_id AS "openingItemId",
			 p.journal_entry_id AS "journalEntryId",j.status AS "journalStatus"
			FROM payments p JOIN journal_entries j ON j.company_id=p.company_id AND j.id=p.journal_entry_id
			""";
	private final JdbcTemplate jdbc;

	public SettlementRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The payments column that links a payment to the document it settles. */
	public enum PaymentDocument {
		SALES_INVOICE("sales_invoice_id"), PURCHASE_BILL("purchase_bill_id"), OPENING_ITEM("opening_item_id");

		private final String column;

		PaymentDocument(String column) {
			this.column = column;
		}
	}

	public List<Map<String, Object>> lockInvoiceStatus(long companyId, long invoiceId) {
		return jdbc.queryForList("SELECT status FROM sales_invoices WHERE company_id=? AND id=? FOR UPDATE", companyId,
				invoiceId);
	}

	public List<Map<String, Object>> lockBillStatus(long companyId, long billId) {
		return jdbc.queryForList("SELECT status FROM purchase_bills WHERE company_id=? AND id=? FOR UPDATE", companyId,
				billId);
	}

	public BigDecimal invoiceOutstanding(long companyId, long invoiceId) {
		return jdbc.queryForObject("SELECT " + INVOICE_OUTSTANDING + " FROM sales_invoices i WHERE i.company_id=? AND i.id=?",
				BigDecimal.class, companyId, invoiceId);
	}

	public BigDecimal billOutstanding(long companyId, long billId) {
		return jdbc.queryForObject("SELECT " + BILL_OUTSTANDING + " FROM purchase_bills b WHERE b.company_id=? AND b.id=?",
				BigDecimal.class, companyId, billId);
	}

	public BigDecimal openingOutstanding(long companyId, long itemId) {
		return jdbc.queryForObject("SELECT " + ITEM_OUTSTANDING
				+ " FROM opening_balance_items o WHERE o.company_id=? AND o.id=?", BigDecimal.class, companyId, itemId);
	}

	public List<Map<String, Object>> lockOpeningItem(long companyId, long itemId) {
		return jdbc.queryForList("SELECT o.item_type AS \"itemType\",o.document_number AS \"number\","
				+ ITEM_SETTLEABLE + " AS settleable FROM " + ITEM_JOIN
				+ " WHERE o.company_id=? AND o.id=? FOR UPDATE OF o", companyId, itemId);
	}

	public Long insertReceipt(long companyId, LocalDate paymentDate, BigDecimal amount, BigDecimal tds, long bank,
			String reference, PaymentDocument document, long documentId, long journalId, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO payments(company_id,direction,payment_date,amount,tds_amount,bank_account_id,reference,"
						+ document.column + ",journal_entry_id,created_by_user_id) VALUES (?,'RECEIPT',?,?,?,?,?,?,?,?) RETURNING id",
				Long.class, companyId, paymentDate, amount, tds, bank, reference, documentId, journalId, actor);
	}

	public Long insertPayment(long companyId, LocalDate paymentDate, BigDecimal amount, long bank, String reference,
			PaymentDocument document, long documentId, long journalId, long actor) {
		return jdbc.queryForObject(
				"INSERT INTO payments(company_id,direction,payment_date,amount,tds_amount,bank_account_id,reference,"
						+ document.column + ",journal_entry_id,created_by_user_id) VALUES (?,'PAYMENT',?,?,0,?,?,?,?,?) RETURNING id",
				Long.class, companyId, paymentDate, amount, bank, reference, documentId, journalId, actor);
	}

	public List<Map<String, Object>> findPayments(long companyId) {
		return jdbc.queryForList(PAYMENT_SELECT + " WHERE p.company_id=? ORDER BY p.payment_date DESC,p.id DESC",
				companyId);
	}

	public Map<String, Object> findPayment(long companyId, long id) {
		return jdbc.queryForMap(PAYMENT_SELECT + " WHERE p.company_id=? AND p.id=?", companyId, id);
	}

	public List<Map<String, Object>> findOpenReceivables(long companyId) {
		return jdbc.queryForList("SELECT i.id,i.invoice_number AS \"number\",p.name AS party,i.invoice_date AS \"date\",i.due_date AS \"dueDate\",i.total_amount AS \"totalAmount\","
				+ INVOICE_OUTSTANDING + " AS outstanding,'INVOICE' AS \"documentType\",NULL::bigint AS \"openingItemId\" FROM sales_invoices i JOIN parties p ON p.company_id=i.company_id AND p.id=i.customer_id WHERE i.company_id=? AND i.status='ISSUED'"
				+ " UNION ALL " + openingItems("RECEIVABLE") + " ORDER BY 4,8,1,9",
				companyId, companyId);
	}

	public List<Map<String, Object>> findOpenPayables(long companyId) {
		return jdbc.queryForList("SELECT b.id,b.vendor_invoice_number AS \"number\",p.name AS party,b.bill_date AS \"date\",b.due_date AS \"dueDate\",b.total_amount AS \"totalAmount\","
				+ BILL_OUTSTANDING + " AS outstanding,'BILL' AS \"documentType\",NULL::bigint AS \"openingItemId\" FROM purchase_bills b JOIN parties p ON p.company_id=b.company_id AND p.id=b.vendor_id WHERE b.company_id=? AND b.status='BOOKED'"
				+ " UNION ALL " + openingItems("PAYABLE") + " ORDER BY 4,8,1,9",
				companyId, companyId);
	}

	private static String openingItems(String type) {
		return "SELECT NULL::bigint,o.document_number,p.name,o.document_date,o.due_date,o.amount," + ITEM_OUTSTANDING
				+ ",'OPENING_BALANCE',o.id FROM " + ITEM_JOIN
				+ " JOIN parties p ON p.company_id=o.company_id AND p.id=o.party_id WHERE o.company_id=? AND o.item_type='" + type
				+ "' AND " + ITEM_SETTLEABLE;
	}
}
