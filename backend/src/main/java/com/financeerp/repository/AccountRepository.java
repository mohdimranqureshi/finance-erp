package com.financeerp.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class AccountRepository {
	private final JdbcTemplate jdbc;

	public AccountRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int countByCompanyAndId(long companyId, long accountId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE company_id=? AND id=?", Integer.class,
				companyId, accountId);
		return n == null ? 0 : n;
	}

	public Map<String, Object> insertAccount(long companyId, String code, String name, String accountType,
			Long parentAccountId) {
		return jdbc.queryForMap(
				"INSERT INTO accounts(company_id,code,name,account_type,parent_account_id) VALUES (?,?,?,?,?) RETURNING id,company_id AS \"companyId\",code,name,account_type AS \"accountType\",parent_account_id AS \"parentAccountId\",active",
				companyId, code, name, accountType, parentAccountId);
	}

	public List<Map<String, Object>> findByCompany(long companyId) {
		return jdbc.queryForList(
				"SELECT id,company_id AS \"companyId\",code,name,account_type AS \"accountType\",parent_account_id AS \"parentAccountId\",active FROM accounts WHERE company_id=? ORDER BY code",
				companyId);
	}
}
