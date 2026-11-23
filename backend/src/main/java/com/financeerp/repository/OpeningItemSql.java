package com.financeerp.repository;

/** SQL fragments over opening-balance items shared by the opening-balance and settlement repositories. */
public final class OpeningItemSql {
	private OpeningItemSql() {
	}

	/** True when journal alias j has been undone: legacy REVERSED status or a posted reversal journal. */
	public static final String JOURNAL_REVERSED = """
			(j.status='REVERSED' OR EXISTS (SELECT 1 FROM journal_entries r
			 WHERE r.company_id=j.company_id AND r.id=j.reversed_by_journal_id AND r.status='POSTED'))""";
	/** Outstanding balance of opening item alias o (receipts include TDS deducted by the customer). */
	public static final String ITEM_OUTSTANDING = """
			o.amount
			 - COALESCE((SELECT SUM(x.amount+x.tds_amount) FROM payments x WHERE x.company_id=o.company_id AND x.opening_item_id=o.id),0)
			""";
	/** Joins opening item alias o to its batch b and journal j. */
	public static final String ITEM_JOIN = """
			opening_balance_items o
			 JOIN opening_balance_batches b ON b.company_id=o.company_id AND b.id=o.batch_id
			 JOIN journal_entries j ON j.company_id=b.company_id AND j.id=b.journal_entry_id
			""";
	/** Opening items that can still be settled: batch active and its journal not reversed. */
	public static final String ITEM_SETTLEABLE = "b.status='ACTIVE' AND NOT " + JOURNAL_REVERSED;
}
