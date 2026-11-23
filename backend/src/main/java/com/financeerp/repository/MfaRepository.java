package com.financeerp.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** TOTP columns of app_users and the recovery-code table. */
@Repository
public class MfaRepository {
	private static final String CLEAR_MFA = "mfa_enabled=FALSE,mfa_secret_enc=NULL,mfa_pending_secret_enc=NULL,"
			+ "mfa_pending_created_at=NULL,mfa_enabled_at=NULL,mfa_last_used_step=NULL";

	private final JdbcTemplate jdbc;

	public MfaRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record MfaUser(long id, String email, String passwordHash, boolean enabled, String secretEnc,
			String pendingEnc, Instant pendingCreatedAt, Long lastStep) {
	}

	public boolean isEnabled(String email) {
		List<Boolean> rows = jdbc.query("SELECT mfa_enabled FROM app_users WHERE lower(email)=lower(?)",
				(rs, i) -> rs.getBoolean(1), email);
		return !rows.isEmpty() && rows.get(0);
	}

	/** Row-locks the active user with this email; null when there is none. */
	public MfaUser lockActiveByEmail(String email) {
		try {
			return load("WHERE lower(email)=lower(?) AND active", email, true);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	public MfaUser findById(long userId) {
		try {
			return load("WHERE id=?", userId, false);
		} catch (EmptyResultDataAccessException ex) {
			return null;
		}
	}

	public void storePendingSecret(String encryptedSecret, long userId) {
		jdbc.update("UPDATE app_users SET mfa_pending_secret_enc=?,mfa_pending_created_at=now() WHERE id=?",
				encryptedSecret, userId);
	}

	public void activatePendingSecret(long step, long userId) {
		jdbc.update("UPDATE app_users SET mfa_enabled=TRUE,mfa_secret_enc=mfa_pending_secret_enc,"
				+ "mfa_pending_secret_enc=NULL,mfa_pending_created_at=NULL,mfa_enabled_at=now(),mfa_last_used_step=?,"
				+ "session_version=session_version+1 WHERE id=?", step, userId);
	}

	/** Returns the number of rows updated; 1 only when the step is newer than the last accepted one. */
	public int advanceLastUsedStep(long userId, long step) {
		return jdbc.update(
				"UPDATE app_users SET mfa_last_used_step=? WHERE id=? AND mfa_enabled AND (mfa_last_used_step IS NULL OR mfa_last_used_step<?)",
				step, userId, step);
	}

	public int markRecoveryCodeUsed(long userId, String codeHash) {
		return jdbc.update(
				"UPDATE user_mfa_recovery_codes SET used_at=now() WHERE user_id=? AND code_hash=? AND used_at IS NULL "
						+ "AND EXISTS (SELECT 1 FROM app_users WHERE id=? AND mfa_enabled)",
				userId, codeHash, userId);
	}

	public Integer countUnusedRecoveryCodes(long userId) {
		return jdbc.queryForObject("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id=? AND used_at IS NULL",
				Integer.class, userId);
	}

	public void clearMfaColumns(long userId) {
		jdbc.update("UPDATE app_users SET " + CLEAR_MFA + ",session_version=session_version+1 WHERE id=?", userId);
	}

	public void deleteRecoveryCodes(long userId) {
		jdbc.update("DELETE FROM user_mfa_recovery_codes WHERE user_id=?", userId);
	}

	public void insertRecoveryCode(long userId, String codeHash) {
		jdbc.update("INSERT INTO user_mfa_recovery_codes(user_id,code_hash) VALUES (?,?)", userId, codeHash);
	}

	private MfaUser load(String where, Object arg, boolean forUpdate) {
		return jdbc.queryForObject(
				"SELECT id,email,password_hash,mfa_enabled,mfa_secret_enc,mfa_pending_secret_enc,mfa_pending_created_at,"
						+ "mfa_last_used_step FROM app_users " + where + (forUpdate ? " FOR UPDATE" : ""),
				(rs, i) -> {
					Timestamp pending = rs.getTimestamp("mfa_pending_created_at");
					long last = rs.getLong("mfa_last_used_step");
					Long lastStep = rs.wasNull() ? null : last;
					return new MfaUser(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
							rs.getBoolean("mfa_enabled"), rs.getString("mfa_secret_enc"),
							rs.getString("mfa_pending_secret_enc"), pending == null ? null : pending.toInstant(),
							lastStep);
				}, arg);
	}
}
