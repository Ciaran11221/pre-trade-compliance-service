package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every read and write against the limit_change_* audit tables and setting_value, in one place, as
 * plain JdbcTemplate rather than JPA entities: these tables are insert-only, so there is never an
 * update to model, and LimitChangeService needs precise control over locking (lockRequest) that an
 * ORM would only get in the way of.
 */
@Component
public class LimitChangeRepository {

	public record RequestRow(long id, String settingKey, BigDecimal oldValue, BigDecimal newValue, String direction,
			String reason, String requestedBy, Instant requestedAt) {
	}

	public record PreviewRow(long requestId, BigDecimal usdNewlyAllowed, boolean hidesBreach, int requiredApprovals,
			String detail) {
	}

	public record ApprovalRow(long id, long requestId, String approver, String approverTeam, String approverRoles,
			Instant approvedAt) {
	}

	public record ActivationRow(long requestId, Instant activatesAt, Instant recordedAt) {
	}

	public record CancellationRow(long requestId, String cancelledBy, Instant cancelledAt) {
	}

	private final JdbcTemplate jdbcTemplate;

	public LimitChangeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Locks the request row for the rest of the caller's transaction. See rule 9 (concurrency). */
	public Optional<RequestRow> lockRequest(long id) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, setting_key, old_value, new_value, direction, reason, requested_by, requested_at
					FROM limit_change_request WHERE id = ? FOR UPDATE
					""", this::mapRequest, id));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<RequestRow> findRequest(long id) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, setting_key, old_value, new_value, direction, reason, requested_by, requested_at
					FROM limit_change_request WHERE id = ?
					""", this::mapRequest, id));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private RequestRow mapRequest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new RequestRow(rs.getLong("id"), rs.getString("setting_key"), rs.getBigDecimal("old_value"),
				rs.getBigDecimal("new_value"), rs.getString("direction"), rs.getString("reason"),
				rs.getString("requested_by"), rs.getTimestamp("requested_at").toInstant());
	}

	public Optional<PreviewRow> findPreview(long requestId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT request_id, usd_newly_allowed, hides_breach, required_approvals, detail::text AS detail
					FROM limit_change_preview WHERE request_id = ?
					""",
					(rs, rowNum) -> new PreviewRow(rs.getLong("request_id"), rs.getBigDecimal("usd_newly_allowed"),
							rs.getBoolean("hides_breach"), rs.getInt("required_approvals"), rs.getString("detail")),
					requestId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public List<ApprovalRow> findApprovals(long requestId) {
		return jdbcTemplate.query("""
				SELECT id, request_id, approver, approver_team, approver_roles, approved_at
				FROM limit_change_approval WHERE request_id = ? ORDER BY approved_at, id
				""",
				(rs, rowNum) -> new ApprovalRow(rs.getLong("id"), rs.getLong("request_id"), rs.getString("approver"),
						rs.getString("approver_team"), rs.getString("approver_roles"),
						rs.getTimestamp("approved_at").toInstant()),
				requestId);
	}

	public boolean hasApproved(long requestId, String approver) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM limit_change_approval WHERE request_id = ? AND approver = ?", Integer.class,
				requestId, approver);
		return count != null && count > 0;
	}

	public Optional<ActivationRow> findActivation(long requestId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT request_id, activates_at, recorded_at FROM limit_change_activation WHERE request_id = ?
					""",
					(rs, rowNum) -> new ActivationRow(rs.getLong("request_id"),
							rs.getTimestamp("activates_at").toInstant(), rs.getTimestamp("recorded_at").toInstant()),
					requestId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<CancellationRow> findCancellation(long requestId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT request_id, cancelled_by, cancelled_at FROM limit_change_cancellation WHERE request_id = ?
					""",
					(rs, rowNum) -> new CancellationRow(rs.getLong("request_id"), rs.getString("cancelled_by"),
							rs.getTimestamp("cancelled_at").toInstant()),
					requestId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public long insertRequest(String settingKey, BigDecimal oldValue, BigDecimal newValue, String direction,
			String reason, String requestedBy, Instant requestedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO limit_change_request (setting_key, old_value, new_value, direction, reason,
				    requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", Long.class, settingKey, oldValue, newValue, direction, reason, requestedBy,
				Timestamp.from(requestedAt));
	}

	public void insertPreview(long requestId, BigDecimal usdNewlyAllowed, boolean hidesBreach, int requiredApprovals,
			String detailJson) {
		jdbcTemplate.update("""
				INSERT INTO limit_change_preview (request_id, usd_newly_allowed, hides_breach, required_approvals,
				    detail) VALUES (?, ?, ?, ?, ?::jsonb)
				""", requestId, usdNewlyAllowed, hidesBreach, requiredApprovals, detailJson);
	}

	public void insertApproval(long requestId, String approver, String approverTeam, String approverRoles,
			Instant approvedAt) {
		jdbcTemplate.update("""
				INSERT INTO limit_change_approval (request_id, approver, approver_team, approver_roles, approved_at)
				VALUES (?, ?, ?, ?, ?)
				""", requestId, approver, approverTeam, approverRoles, Timestamp.from(approvedAt));
	}

	public void insertActivation(long requestId, Instant activatesAt, Instant recordedAt) {
		jdbcTemplate.update(
				"INSERT INTO limit_change_activation (request_id, activates_at, recorded_at) VALUES (?, ?, ?)",
				requestId, Timestamp.from(activatesAt), Timestamp.from(recordedAt));
	}

	public void insertCancellation(long requestId, String cancelledBy, Instant cancelledAt) {
		jdbcTemplate.update(
				"INSERT INTO limit_change_cancellation (request_id, cancelled_by, cancelled_at) VALUES (?, ?, ?)",
				requestId, cancelledBy, Timestamp.from(cancelledAt));
	}

	public void insertSettingValue(String settingKey, BigDecimal value, Instant activeFrom, long changeRequestId) {
		jdbcTemplate.update(
				"INSERT INTO setting_value (setting_key, value, active_from, change_request_id) VALUES (?, ?, ?, ?)",
				settingKey, value, Timestamp.from(activeFrom), changeRequestId);
	}

	public void insertRefusal(String requester, String settingKey, BigDecimal requestedValue, String reason,
			Instant refusedAt) {
		jdbcTemplate.update("""
				INSERT INTO limit_change_refusal (requester, setting_key, requested_value, reason, refused_at)
				VALUES (?, ?, ?, ?, ?)
				""", requester, settingKey, requestedValue, reason, Timestamp.from(refusedAt));
	}

}
