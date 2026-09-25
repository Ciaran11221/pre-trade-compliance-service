package io.github.ciaran11221.compliance.audit;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Every audit table (V2__audit_insert_only.sql) must reject UPDATE, DELETE
 * and TRUNCATE, with the error naming the table. Driven from one list
 * ({@link #AUDIT_TABLES}) so a new audit table is one line.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AuditTablesInsertOnlyTest {

	private record AuditTable(String name, String primaryKeyColumn) {
	}

	private static final List<AuditTable> AUDIT_TABLES = List.of(
			new AuditTable("trade_order", "id"),
			new AuditTable("order_event", "id"),
			new AuditTable("decision", "id"),
			new AuditTable("rule_result", "id"),
			new AuditTable("quarantine", "id"),
			new AuditTable("quarantine_resolution", "id"),
			new AuditTable("setting_value", "id"),
			new AuditTable("limit_change_request", "id"),
			new AuditTable("limit_change_preview", "request_id"),
			new AuditTable("limit_change_approval", "id"),
			new AuditTable("limit_change_activation", "request_id"),
			new AuditTable("limit_change_cancellation", "request_id"),
			new AuditTable("limit_change_refusal", "id"));

	@Autowired
	private JdbcTemplate jdbc;

	private Long fundId;

	private Long securityId;

	private Long orderId;

	private Long decisionId;

	private Long quarantineId;

	private Long requestId;

	@BeforeEach
	void seedOneRowPerAuditTable() {
		fundId = jdbc.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);
		securityId = jdbc.queryForObject("SELECT id FROM security WHERE ticker = 'KSTL'", Long.class);

		int inserted = jdbc.update(
				"INSERT INTO trade_order (client_order_id, fund_id, security_id, side, quantity, "
						+ "reference_price, submitted_by, submitted_at, request_hash) "
						+ "VALUES (?, ?, ?, 'BUY', 100, 100.0000, 'anne', now(), 'hash-1')",
				"audit-test-order-1", fundId, securityId);
		assertThat(inserted).isEqualTo(1);
		orderId = jdbc.queryForObject("SELECT id FROM trade_order WHERE client_order_id = 'audit-test-order-1'",
				Long.class);

		jdbc.update("INSERT INTO order_event (order_id, event_type, actor, occurred_at, detail) "
				+ "VALUES (?, 'DECIDED', 'anne', now(), '{}'::jsonb)", orderId);

		jdbc.update("INSERT INTO decision (order_id, outcome, decided_at, settings_snapshot, inputs_snapshot) "
				+ "VALUES (?, 'PASS', now(), '{}'::jsonb, '{}'::jsonb)", orderId);
		decisionId = jdbc.queryForObject("SELECT id FROM decision WHERE order_id = ?", Long.class, orderId);

		jdbc.update(
				"INSERT INTO rule_result (decision_id, rule_name, outcome, reason, measured_value, limit_value) "
						+ "VALUES (?, 'issuer-limit', 'PASS', 'within limit', 1.0000, 5.0000)",
				decisionId);

		jdbc.update("INSERT INTO quarantine (order_id, reason, quarantined_at, expires_at) "
				+ "VALUES (?, 'POSSIBLE_DUPLICATE', now(), now() + interval '30 minutes')", orderId);
		quarantineId = jdbc.queryForObject("SELECT id FROM quarantine WHERE order_id = ?", Long.class, orderId);

		jdbc.update("INSERT INTO quarantine_resolution (quarantine_id, resolution, resolved_by, resolved_at) "
				+ "VALUES (?, 'RELEASED', 'sup-1', now())", quarantineId);

		jdbc.update(
				"INSERT INTO limit_change_request (setting_key, old_value, new_value, direction, reason, "
						+ "requested_by, requested_at) VALUES ('ISSUER_LIMIT_PCT', 5, 6, 'LOOSEN', "
						+ "'audit test request', 'comp-1', now())");
		requestId = jdbc.queryForObject(
				"SELECT id FROM limit_change_request WHERE reason = 'audit test request'", Long.class);

		jdbc.update("INSERT INTO limit_change_preview (request_id, usd_newly_allowed, hides_breach, "
				+ "required_approvals, detail) VALUES (?, 1000000.0000, false, 2, '{}'::jsonb)", requestId);

		jdbc.update("INSERT INTO limit_change_approval (request_id, approver, approver_team, approver_roles, "
				+ "approved_at) VALUES (?, 'comp-1', 'compliance', 'compliance-officer', now())", requestId);

		jdbc.update("INSERT INTO limit_change_activation (request_id, activates_at, recorded_at) "
				+ "VALUES (?, now(), now())", requestId);

		jdbc.update("INSERT INTO limit_change_cancellation (request_id, cancelled_by, cancelled_at) "
				+ "VALUES (?, 'exec-1', now())", requestId);

		jdbc.update("INSERT INTO limit_change_refusal (requester, setting_key, requested_value, reason, refused_at) "
				+ "VALUES ('comp-1', 'ISSUER_LIMIT_PCT', 40, 'ISSUER_LIMIT_PCT must be <= 5', now())");
	}

	@TestFactory
	List<DynamicTest> everyAuditTableRejectsUpdateDeleteAndTruncate() {
		return AUDIT_TABLES.stream()
			.flatMap(table -> List.of(
					dynamicTest(table.name() + " rejects UPDATE", () -> assertUpdateRejected(table)),
					dynamicTest(table.name() + " rejects DELETE", () -> assertDeleteRejected(table)),
					dynamicTest(table.name() + " rejects TRUNCATE", () -> assertTruncateRejected(table)))
				.stream())
			.toList();
	}

	private void assertUpdateRejected(AuditTable table) {
		Object pk = jdbc.queryForObject(
				"SELECT " + table.primaryKeyColumn() + " FROM " + table.name() + " LIMIT 1", Object.class);

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE " + table.name() + " SET " + table.primaryKeyColumn() + " = " + table.primaryKeyColumn()
						+ " WHERE " + table.primaryKeyColumn() + " = ?",
				pk))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining(table.name())
			.hasMessageContaining("insert-only");
	}

	private void assertDeleteRejected(AuditTable table) {
		Object pk = jdbc.queryForObject(
				"SELECT " + table.primaryKeyColumn() + " FROM " + table.name() + " LIMIT 1", Object.class);

		assertThatThrownBy(() -> jdbc.update(
				"DELETE FROM " + table.name() + " WHERE " + table.primaryKeyColumn() + " = ?", pk))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining(table.name())
			.hasMessageContaining("insert-only");
	}

	private void assertTruncateRejected(AuditTable table) {
		// CASCADE, not a bare TRUNCATE: some of these tables are themselves
		// referenced by other audit tables (trade_order by order_event, for
		// instance), and a bare TRUNCATE on those is refused by Postgres's own
		// FK-dependency check before our trigger ever runs. CASCADE reaches
		// the table's own BEFORE TRUNCATE trigger, which is the thing under
		// test here.
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE " + table.name() + " CASCADE"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining(table.name())
			.hasMessageContaining("insert-only");
	}

}
