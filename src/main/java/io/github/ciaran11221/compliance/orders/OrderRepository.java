package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.ciaran11221.compliance.rules.OrderContext;

/**
 * Every read and write against trade_order, order_event, decision and rule_result, in one place,
 * as plain JdbcTemplate -- same reasoning as LimitChangeRepository: these tables are insert-only,
 * so there is never an update to model, and OrderService needs precise control over locking
 * (lockFund) an ORM would only get in the way of.
 */
@Component
public class OrderRepository {

	/**
	 * Decision outcomes that count as "pending exposure" for cash and diversification purposes
	 * (spec 3.1) while the order carrying that decision is not yet filled or cancelled -- see
	 * findPendingOrders. This is the one place M7b's quarantine seam (issue #14) needs to touch:
	 * a quarantined order has no decision row yet (quarantine runs BEFORE the compliance engine),
	 * so counting it as pending exposure needs a second branch alongside this outcome filter, not
	 * an addition to this list. See OrderService.submit's SEAM comment for where that plugs in.
	 */
	public static final List<String> PENDING_EXPOSURE_OUTCOMES = List.of("PASS");

	private static final List<String> TERMINAL_EVENT_TYPES = List.of("FILLED", "CANCELLED");

	private final JdbcTemplate jdbcTemplate;

	public OrderRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public record FundRow(long id, String code, String name, BigDecimal totalAssets, BigDecimal cash,
			boolean diversified) {
	}

	public record SecurityRow(long id, String ticker, String issuerName, BigDecimal price,
			long votingSharesOutstanding, long avgDailyVolume) {
	}

	public record OrderRow(long id, String clientOrderId, long fundId, long securityId, String ticker, String side,
			long quantity, BigDecimal referencePrice, String submittedBy, Instant submittedAt, String requestHash) {
	}

	public record DecisionRow(long id, long orderId, String outcome, Instant decidedAt, String settingsSnapshot,
			String inputsSnapshot) {
	}

	public record RuleResultRow(long id, long decisionId, String ruleName, String outcome, String reason,
			BigDecimal measuredValue, BigDecimal limitValue) {
	}

	/**
	 * Locks the fund row for the rest of the caller's transaction. See OrderService.submit's
	 * Javadoc for why this is taken before the idempotency lookup, and the "Two different buys"
	 * concurrency test for why it must stay held across the whole build-context-then-insert
	 * sequence (Spring's @Transactional keeps the row lock until commit, which is what makes the
	 * second order's pending-exposure read see the first order's already-committed decision).
	 */
	public Optional<FundRow> lockFund(long fundId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, code, name, total_assets, cash, diversified FROM fund WHERE id = ? FOR UPDATE
					""", this::mapFund, fundId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<FundRow> findFund(long fundId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, code, name, total_assets, cash, diversified FROM fund WHERE id = ?
					""", this::mapFund, fundId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private FundRow mapFund(ResultSet rs, int rowNum) throws SQLException {
		return new FundRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
				rs.getBigDecimal("total_assets"), rs.getBigDecimal("cash"), rs.getBoolean("diversified"));
	}

	public Optional<SecurityRow> findSecurityByTicker(String ticker) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, ticker, issuer_name, price, voting_shares_outstanding, avg_daily_volume
					FROM security WHERE ticker = ?
					""", this::mapSecurity, ticker));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	private SecurityRow mapSecurity(ResultSet rs, int rowNum) throws SQLException {
		return new SecurityRow(rs.getLong("id"), rs.getString("ticker"), rs.getString("issuer_name"),
				rs.getBigDecimal("price"), rs.getLong("voting_shares_outstanding"), rs.getLong("avg_daily_volume"));
	}

	/**
	 * Every security's reference data, keyed by ticker: OrderContext needs price/issuer/voting data
	 * for every security a rule might touch (holdings, pending orders, the order itself), not just
	 * the one being traded -- see OrderContext's Javadoc. The reference table is small, so loading
	 * it whole is simpler than working out which subset a given order's rules will need.
	 */
	public Map<String, OrderContext.SecurityInfo> findAllSecurityInfo() {
		List<Map.Entry<String, OrderContext.SecurityInfo>> rows = jdbcTemplate.query(
				"SELECT ticker, issuer_name, price, voting_shares_outstanding, avg_daily_volume FROM security",
				(rs, rowNum) -> Map.entry(rs.getString("ticker"),
						new OrderContext.SecurityInfo(rs.getString("issuer_name"), rs.getBigDecimal("price"),
								rs.getLong("voting_shares_outstanding"), rs.getLong("avg_daily_volume"))));
		Map<String, OrderContext.SecurityInfo> securities = new LinkedHashMap<>();
		rows.forEach(row -> securities.put(row.getKey(), row.getValue()));
		return securities;
	}

	public Map<String, Long> findHoldings(long fundId) {
		List<Map.Entry<String, Long>> rows = jdbcTemplate.query("""
				SELECT s.ticker, h.quantity FROM holding h JOIN security s ON s.id = h.security_id
				WHERE h.fund_id = ?
				""", (rs, rowNum) -> Map.entry(rs.getString("ticker"), rs.getLong("quantity")), fundId);
		Map<String, Long> holdings = new LinkedHashMap<>();
		rows.forEach(row -> holdings.put(row.getKey(), row.getValue()));
		return holdings;
	}

	public Set<String> findRestrictedTickers() {
		List<String> tickers = jdbcTemplate.query("""
				SELECT s.ticker FROM restricted_security rs JOIN security s ON s.id = rs.security_id
				""", (rs, rowNum) -> rs.getString("ticker"));
		return new LinkedHashSet<>(tickers);
	}

	/**
	 * Orders for this fund whose decision outcome is still in PENDING_EXPOSURE_OUTCOMES and that
	 * have not since been filled or cancelled (order_event carries no update, so "not yet filled or
	 * cancelled" is simply "no FILLED/CANCELLED event exists for this order").
	 */
	public List<OrderContext.PendingOrder> findPendingOrders(long fundId) {
		String outcomePlaceholders = PENDING_EXPOSURE_OUTCOMES.stream().map(o -> "?").collect(Collectors.joining(","));
		String terminalPlaceholders = TERMINAL_EVENT_TYPES.stream().map(o -> "?").collect(Collectors.joining(","));
		String sql = """
				SELECT o.side, s.ticker, o.quantity, o.reference_price
				FROM trade_order o
				JOIN decision d ON d.order_id = o.id
				JOIN security s ON s.id = o.security_id
				WHERE o.fund_id = ?
				  AND d.outcome IN (%s)
				  AND NOT EXISTS (
				      SELECT 1 FROM order_event oe
				      WHERE oe.order_id = o.id AND oe.event_type IN (%s)
				  )
				""".formatted(outcomePlaceholders, terminalPlaceholders);
		List<Object> args = new ArrayList<>();
		args.add(fundId);
		args.addAll(PENDING_EXPOSURE_OUTCOMES);
		args.addAll(TERMINAL_EVENT_TYPES);
		return jdbcTemplate.query(sql,
				(rs, rowNum) -> new OrderContext.PendingOrder(OrderContext.Side.valueOf(rs.getString("side")),
						rs.getString("ticker"), rs.getLong("quantity"), rs.getBigDecimal("reference_price")),
				args.toArray());
	}

	public Optional<OrderRow> findOrderByClientOrderId(String clientOrderId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject(SELECT_ORDER + " WHERE o.client_order_id = ?",
					this::mapOrder, clientOrderId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public Optional<OrderRow> findOrder(long id) {
		try {
			return Optional.of(jdbcTemplate.queryForObject(SELECT_ORDER + " WHERE o.id = ?", this::mapOrder, id));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public List<OrderRow> findOrdersByFund(long fundId, int limit, int offset) {
		return jdbcTemplate.query(SELECT_ORDER + " WHERE o.fund_id = ? ORDER BY o.submitted_at DESC, o.id DESC LIMIT ? OFFSET ?",
				this::mapOrder, fundId, limit, offset);
	}

	public long countOrdersByFund(long fundId) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE fund_id = ?", Long.class,
				fundId);
		return count != null ? count : 0L;
	}

	private static final String SELECT_ORDER = """
			SELECT o.id, o.client_order_id, o.fund_id, o.security_id, s.ticker, o.side, o.quantity,
			       o.reference_price, o.submitted_by, o.submitted_at, o.request_hash
			FROM trade_order o JOIN security s ON s.id = o.security_id
			""";

	private OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
		return new OrderRow(rs.getLong("id"), rs.getString("client_order_id"), rs.getLong("fund_id"),
				rs.getLong("security_id"), rs.getString("ticker"), rs.getString("side"), rs.getLong("quantity"),
				rs.getBigDecimal("reference_price"), rs.getString("submitted_by"),
				rs.getTimestamp("submitted_at").toInstant(), rs.getString("request_hash"));
	}

	/**
	 * Empty when another transaction already holds this clientOrderId. ON CONFLICT DO NOTHING rather
	 * than letting the unique key throw: in Postgres a failed statement aborts the whole
	 * transaction, so after a duplicate-key error the caller could not even look up the winning
	 * order to replay it. The insert waits for the other transaction to commit, so a follow-up read
	 * sees the winner's row.
	 */
	public Optional<Long> insertOrder(String clientOrderId, long fundId, long securityId, OrderContext.Side side,
			long quantity, BigDecimal referencePrice, String submittedBy, Instant submittedAt, String requestHash) {
		return jdbcTemplate.query("""
				INSERT INTO trade_order (client_order_id, fund_id, security_id, side, quantity, reference_price,
				    submitted_by, submitted_at, request_hash)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (client_order_id) DO NOTHING
				RETURNING id
				""", (rs, rowNum) -> rs.getLong("id"), clientOrderId, fundId, securityId, side.name(), quantity,
				referencePrice, submittedBy, Timestamp.from(submittedAt), requestHash)
			.stream()
			.findFirst();
	}

	public void insertOrderEvent(long orderId, String eventType, String actor, Instant occurredAt, String detailJson) {
		jdbcTemplate.update(
				"INSERT INTO order_event (order_id, event_type, actor, occurred_at, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
				orderId, eventType, actor, Timestamp.from(occurredAt), detailJson);
	}

	public long insertDecision(long orderId, String outcome, Instant decidedAt, String settingsSnapshotJson,
			String inputsSnapshotJson) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO decision (order_id, outcome, decided_at, settings_snapshot, inputs_snapshot)
				VALUES (?, ?, ?, ?::jsonb, ?::jsonb) RETURNING id
				""", Long.class, orderId, outcome, Timestamp.from(decidedAt), settingsSnapshotJson, inputsSnapshotJson);
	}

	public void insertRuleResult(long decisionId, String ruleName, String outcome, String reason,
			BigDecimal measuredValue, BigDecimal limitValue) {
		jdbcTemplate.update("""
				INSERT INTO rule_result (decision_id, rule_name, outcome, reason, measured_value, limit_value)
				VALUES (?, ?, ?, ?, ?, ?)
				""", decisionId, ruleName, outcome, reason, measuredValue, limitValue);
	}

	public Optional<DecisionRow> findDecisionByOrderId(long orderId) {
		try {
			return Optional.of(jdbcTemplate.queryForObject("""
					SELECT id, order_id, outcome, decided_at, settings_snapshot::text AS settings_snapshot,
					       inputs_snapshot::text AS inputs_snapshot
					FROM decision WHERE order_id = ?
					""",
					(rs, rowNum) -> new DecisionRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("outcome"),
							rs.getTimestamp("decided_at").toInstant(), rs.getString("settings_snapshot"),
							rs.getString("inputs_snapshot")),
					orderId));
		}
		catch (EmptyResultDataAccessException ex) {
			return Optional.empty();
		}
	}

	public List<RuleResultRow> findRuleResults(long decisionId) {
		return jdbcTemplate.query("""
				SELECT id, decision_id, rule_name, outcome, reason, measured_value, limit_value
				FROM rule_result WHERE decision_id = ? ORDER BY rule_name
				""",
				(rs, rowNum) -> new RuleResultRow(rs.getLong("id"), rs.getLong("decision_id"), rs.getString("rule_name"),
						rs.getString("outcome"), rs.getString("reason"), rs.getBigDecimal("measured_value"),
						rs.getBigDecimal("limit_value")),
				decisionId);
	}

	/** The most recent order_event's type for this order: DECIDED, FILLED or CANCELLED in M7a. */
	public Optional<String> findLatestEventType(long orderId) {
		List<String> result = jdbcTemplate.query(
				"SELECT event_type FROM order_event WHERE order_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1",
				(rs, rowNum) -> rs.getString("event_type"), orderId);
		return result.stream().findFirst();
	}

}
