package io.github.ciaran11221.compliance.quarantine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.ciaran11221.compliance.orders.OrderRepository;

/**
 * Every read and write this package needs against the quarantine and quarantine_resolution audit
 * tables, as plain JdbcTemplate -- same reasoning as orders.OrderRepository and
 * limits.LimitChangeRepository: insert-only tables need no update to model, and release/reject
 * need precise control over locking (lockQuarantineByOrderId) an ORM would only get in the way of.
 * orders.OrderRepository also reads the quarantine table directly (findPendingOrders,
 * findOpenQuarantineExpiry, findQuarantineByOrderId, findRecentOrdersForLookback) -- there is no
 * Java-level dependency either way for that, only two repositories running their own SQL against
 * shared tables, the same relationship LimitsRepository and LimitChangeRepository already have
 * with setting_value.
 */
@Component
public class QuarantineRepository {

	public record ResolutionRow(long quarantineId, String resolution, String resolvedBy, Instant resolvedAt) {
	}

	/** One row for GET /api/quarantine: the quarantine plus the order fields a caller needs to see. */
	public record OpenQuarantineRow(long orderId, long fundId, String ticker, String side, long quantity,
			String submittedBy, String reason, Long matchedOrderId, Instant quarantinedAt, Instant expiresAt,
			String assignedTo) {
	}

	private final JdbcTemplate jdbcTemplate;

	public QuarantineRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Locks the quarantine row for the rest of the caller's transaction (spec 3.3's concurrency
	 * done-when item: two supervisors resolving the same quarantine at once must produce exactly one
	 * resolution). Whichever transaction gets here second blocks until the first commits or rolls
	 * back, then re-reads and finds the resolution the first one wrote -- see QuarantineService's
	 * release/reject, mirroring limits.LimitChangeRepository.lockRequest.
	 */
	public Optional<OrderRepository.QuarantineRow> lockQuarantineByOrderId(long orderId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, order_id, reason, matched_order_id, quarantined_at, expires_at, assigned_to
					FROM quarantine WHERE order_id = ? FOR UPDATE
					""", this::mapQuarantine, orderId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private OrderRepository.QuarantineRow mapQuarantine(ResultSet rs, int rowNum) throws SQLException {
		long matched = rs.getLong("matched_order_id");
		return new OrderRepository.QuarantineRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("reason"),
				rs.wasNull() ? null : matched, rs.getTimestamp("quarantined_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant(), rs.getString("assigned_to"));
	}

	public Optional<ResolutionRow> findResolution(long quarantineId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT quarantine_id, resolution, resolved_by, resolved_at
					FROM quarantine_resolution WHERE quarantine_id = ?
					""",
					(rs, rowNum) -> new ResolutionRow(rs.getLong("quarantine_id"), rs.getString("resolution"),
							rs.getString("resolved_by"), rs.getTimestamp("resolved_at").toInstant()),
					quarantineId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	/**
	 * ON CONFLICT DO NOTHING rather than an insert the UNIQUE constraint could reject (trap #5: a
	 * failed statement aborts the whole Postgres transaction, so a caught DuplicateKeyException
	 * followed by another query on the same connection would 500). Returns whether THIS call
	 * actually created the row -- false means someone else (a concurrent release/reject, or the
	 * expiry job) already had, which the caller turns into 409, never a second write.
	 */
	public boolean insertResolutionIfAbsent(long quarantineId, String resolution, String resolvedBy,
			Instant resolvedAt) {
		List<Long> inserted = jdbcTemplate.query("""
				INSERT INTO quarantine_resolution (quarantine_id, resolution, resolved_by, resolved_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (quarantine_id) DO NOTHING
				RETURNING id
				""", (rs, rowNum) -> rs.getLong("id"), quarantineId, resolution, resolvedBy, Timestamp.from(resolvedAt));
		return !inserted.isEmpty();
	}

	/**
	 * Open quarantines (spec 3.3's GET /api/quarantine): not yet resolved AND not overdue -- an
	 * overdue-but-unresolved one is excluded here exactly as every other read treats it, as EXPIRED
	 * (issue #14), so it never shows up as still open just because QuarantineExpiryJob has not run
	 * yet. assignedTo, when non-null, filters to that one assignee (assignedToMe=true).
	 */
	public List<OpenQuarantineRow> findOpenQuarantines(Instant now, String assignedTo) {
		String sql = """
				SELECT o.id AS order_id, o.fund_id, s.ticker, o.side, o.quantity, o.submitted_by,
				       q.reason, q.matched_order_id, q.quarantined_at, q.expires_at, q.assigned_to
				FROM quarantine q
				JOIN trade_order o ON o.id = q.order_id
				JOIN security s ON s.id = o.security_id
				WHERE q.expires_at > ?
				  AND NOT EXISTS (SELECT 1 FROM quarantine_resolution qr WHERE qr.quarantine_id = q.id)
				""" + (assignedTo != null ? " AND q.assigned_to = ?" : "") + """

				ORDER BY q.quarantined_at ASC, q.id ASC
				""";
		Object[] args = assignedTo != null ? new Object[] { Timestamp.from(now), assignedTo }
				: new Object[] { Timestamp.from(now) };
		return jdbcTemplate.query(sql, this::mapOpenQuarantineRow, args);
	}

	private OpenQuarantineRow mapOpenQuarantineRow(ResultSet rs, int rowNum) throws SQLException {
		long matched = rs.getLong("matched_order_id");
		return new OpenQuarantineRow(rs.getLong("order_id"), rs.getLong("fund_id"), rs.getString("ticker"),
				rs.getString("side"), rs.getLong("quantity"), rs.getString("submitted_by"), rs.getString("reason"),
				rs.wasNull() ? null : matched, rs.getTimestamp("quarantined_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant(), rs.getString("assigned_to"));
	}

	/** Every quarantine past its expiry with no resolution yet -- QuarantineExpiryJob's candidates. */
	public List<Long> findOverdueUnresolvedOrderIds(Instant now) {
		return jdbcTemplate.query("""
				SELECT q.order_id FROM quarantine q
				WHERE q.expires_at <= ?
				  AND NOT EXISTS (SELECT 1 FROM quarantine_resolution qr WHERE qr.quarantine_id = q.id)
				ORDER BY q.order_id
				""", (rs, rowNum) -> rs.getLong("order_id"), Timestamp.from(now));
	}

}
