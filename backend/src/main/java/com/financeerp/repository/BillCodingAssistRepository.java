package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class BillCodingAssistRepository {
	private final JdbcTemplate jdbc;

	public BillCodingAssistRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> findActiveExpenseAccounts(long companyId, String code) {
		return jdbc.queryForList(
				"SELECT id,code,name FROM accounts WHERE company_id=? AND code=? AND active=true AND account_type IN ('EXPENSE')",
				companyId, code);
	}
}
