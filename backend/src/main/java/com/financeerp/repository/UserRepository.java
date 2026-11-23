package com.financeerp.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** app_users access for sign-in, account administration and bootstrap, plus the global USER audit rows they write. */
@Repository
public class UserRepository {
	private final JdbcTemplate jdbc;

	public UserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Credentials(long id, String email, String passwordHash, boolean active, boolean admin,
			boolean mfaEnabled) {
	}

	public Map<String, Object> insertUser(String email, String passwordHash, String displayName) {
		return jdbc.queryForMap(
				"INSERT INTO app_users(email,password_hash,display_name) VALUES (?,?,?) RETURNING id,email,display_name AS \"displayName\",created_at AS \"createdAt\"",
				email, passwordHash, displayName);
	}

	public void insertUserCreatedAudit(String actor, String userId, String email) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,'USER_CREATED','USER',?,jsonb_build_object('email',?))",
				actor, userId, email);
	}

	public void insertUserEmailAudit(String actor, String action, String userId, String email) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,?,'USER',?,jsonb_build_object('email',?))",
				actor, action, userId, email);
	}

	public void insertBootstrapAdminAudit(String actor, String userId, String email) {
		jdbc.update(
				"INSERT INTO audit_events(company_id,actor,action,entity_type,entity_id,details) VALUES (NULL,?,'BOOTSTRAP_ADMIN_CREATED','USER',?,jsonb_build_object('email',?))",
				actor, userId, email);
	}

	public Credentials findCredentialsByEmail(String email) {
		return jdbc.query(
				"SELECT id,email,password_hash,active,is_admin,mfa_enabled FROM app_users WHERE lower(email)=lower(?)",
				rs -> rs.next() ? new Credentials(rs.getLong("id"), rs.getString("email"),
						rs.getString("password_hash"), rs.getBoolean("active"), rs.getBoolean("is_admin"),
						rs.getBoolean("mfa_enabled")) : null,
				email);
	}

	public Integer findSessionVersionById(long userId) {
		return jdbc.queryForObject("SELECT session_version FROM app_users WHERE id=?", Integer.class, userId);
	}

	public Integer findSessionVersionByEmail(String email) {
		return jdbc.queryForObject("SELECT session_version FROM app_users WHERE lower(email)=lower(?)",
				Integer.class, email);
	}

	/** Null when the exact email has no active account. */
	public Integer findActiveSessionVersion(String email) {
		List<Integer> rows = jdbc.query("SELECT session_version FROM app_users WHERE email=? AND active",
				(rs, i) -> rs.getInt(1), email);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public Integer countByEmail(String email) {
		return jdbc.queryForObject("SELECT count(*) FROM app_users WHERE lower(email)=lower(?)", Integer.class,
				email);
	}

	public void insertAdmin(String email, String passwordHash, String displayName) {
		jdbc.update("INSERT INTO app_users(email,password_hash,display_name,is_admin) VALUES (?,?,?,TRUE)", email,
				passwordHash, displayName);
	}

	public List<Map<String, Object>> listUsers() {
		return jdbc.queryForList(
				"SELECT u.id,u.email,u.display_name AS \"displayName\",u.active,u.is_admin AS \"admin\",u.mfa_enabled AS \"mfaEnabled\",u.created_at AS \"createdAt\","
						+ "(SELECT count(*) FROM company_memberships m WHERE m.user_id=u.id) AS \"companyCount\" "
						+ "FROM app_users u ORDER BY u.id");
	}

	/** Null when the user does not exist. */
	public Map<String, Object> findUserById(long userId) {
		try {
			return jdbc.queryForMap(
					"SELECT id,email,display_name AS \"displayName\",active,is_admin AS \"admin\",mfa_enabled AS \"mfaEnabled\",created_at AS \"createdAt\" FROM app_users WHERE id=?",
					userId);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	public Integer countOtherActiveAdmins(long userId) {
		return jdbc.queryForObject("SELECT count(*) FROM app_users WHERE is_admin=true AND active=true AND id<>?",
				Integer.class, userId);
	}

	public void updateActive(long userId, boolean active) {
		jdbc.update("UPDATE app_users SET active=?,session_version=session_version+1 WHERE id=?", active, userId);
	}

	/** Counts rows in a fixed, code-defined accounting table that reference the user. */
	public Integer countUserReferences(String table, String column, long userId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class,
				userId);
	}

	public void deleteMemberships(long userId) {
		jdbc.update("DELETE FROM company_memberships WHERE user_id=?", userId);
	}

	public void deleteUser(long userId) {
		jdbc.update("DELETE FROM app_users WHERE id=?", userId);
	}
}
