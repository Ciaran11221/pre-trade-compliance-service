package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #27: quarantine.assigned_to = NULL used to mean two different things (an eligible
 * SUPERVISOR is in office, so the hold is open to anyone, versus nobody eligible being in office at
 * all), and a reader could not tell them apart. These tests cover the falsifier from the issue and
 * the done-when items not already exercised by QuarantineScenarioTest's S023/S028/S029/S030 (which
 * cover the four escalation outcomes over the HTTP API with the seeded fixture): that a recorded
 * assignment is never recomputed from current out-of-office flags, that a pre-V6 row reads as
 * NOT_RECORDED rather than a guess, and that the database itself refuses a new row with no
 * assignment recorded.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineAssignmentTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineAssignmentTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	private static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	private static final Instant OOO_FROM = Instant.parse("2025-12-01T00:00:00Z");

	private static final Instant OOO_UNTIL = Instant.parse("2026-02-01T00:00:00Z");

	@LocalServerPort
	private int port;

	@Autowired
	private Flyway flyway;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private final RestClient restClient = RestClient.create();

	private long hgfFundId;

	@BeforeEach
	void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
		hgfFundId = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);
	}

	/**
	 * The issue's own falsifier: every SUPERVISOR and the one COMPLIANCE user out of office, then a
	 * duplicate order is held -- the recorded state must be UNASSIGNED, and must NOT be
	 * ANY_SUPERVISOR (the bug this issue reports: both cases used to read as assignedTo=null with no
	 * way to tell them apart).
	 */
	@Test
	void nobodyEligibleInOfficeRecordsUnassignedNotAnySupervisor() throws Exception {
		markOutOfOffice("sup-1");
		markOutOfOffice("sup-2");
		markOutOfOffice("sup-3");
		markOutOfOffice("comp-1");

		readView(submit("falsifier-a", "brian", "BUY", "KSTL", 1_000L));
		OrderView held = readView(submit("falsifier-b", "anne", "BUY", "KSTL", 1_050L));

		assertThat(held.status()).isEqualTo("QUARANTINED");
		assertThat(held.quarantine().assignment())
			.as("nobody eligible was in office: this must be UNASSIGNED, never ANY_SUPERVISOR")
			.isEqualTo("UNASSIGNED");
		assertThat(held.quarantine().assignedTo()).isNull();
	}

	/** An eligible releaser in office: ANY_SUPERVISOR, assignedTo null (the general pool is open). */
	@Test
	void eligibleSupervisorInOfficeRecordsAnySupervisor() throws Exception {
		readView(submit("any-sup-a", "brian", "BUY", "KSTL", 1_000L));
		OrderView held = readView(submit("any-sup-b", "anne", "BUY", "KSTL", 1_050L));

		assertThat(held.status()).isEqualTo("QUARANTINED");
		assertThat(held.quarantine().assignment()).isEqualTo("ANY_SUPERVISOR");
		assertThat(held.quarantine().assignedTo()).isNull();
	}

	/** The sender's backup in office, every other supervisor out: ASSIGNED, assignedTo the backup. */
	@Test
	void backupInOfficeRecordsAssignedToTheBackup() throws Exception {
		markOutOfOffice("sup-1");
		markOutOfOffice("sup-3");
		// sup-2, anne's backup (R__seed.sql), stays in office.

		readView(submit("backup-a", "brian", "BUY", "KSTL", 1_000L));
		OrderView held = readView(submit("backup-b", "anne", "BUY", "KSTL", 1_050L));

		assertThat(held.status()).isEqualTo("QUARANTINED");
		assertThat(held.quarantine().assignment()).isEqualTo("ASSIGNED");
		assertThat(held.quarantine().assignedTo()).isEqualTo("sup-2");
	}

	/**
	 * Recorded, never recomputed: a hold with nobody in office is UNASSIGNED; clearing a supervisor's
	 * out-of-office afterwards must not change what was already written -- a later read still shows
	 * UNASSIGNED, because the state was decided once, when the quarantine was written.
	 */
	@Test
	void recordedAssignmentIsNotRecomputedAfterSomeoneReturnsToOffice() throws Exception {
		markOutOfOffice("sup-1");
		markOutOfOffice("sup-2");
		markOutOfOffice("sup-3");
		markOutOfOffice("comp-1");

		readView(submit("recorded-a", "brian", "BUY", "KSTL", 1_000L));
		OrderView held = readView(submit("recorded-b", "anne", "BUY", "KSTL", 1_050L));
		assertThat(held.quarantine().assignment()).isEqualTo("UNASSIGNED");

		// sup-1 comes back to the office; the sender's own backup (sup-2) and comp-1 are still out.
		clearOutOfOffice("sup-1");

		OrderView reread = readView(getOrder(held.id(), "sup-1"));
		assertThat(reread.quarantine().assignment())
			.as("assignment is recorded once, not recomputed from today's out-of-office flags")
			.isEqualTo("UNASSIGNED");
		assertThat(reread.quarantine().assignedTo()).isNull();
	}

	/**
	 * A quarantine row written before V6 (no assignment column at insert time) must read as
	 * NOT_RECORDED -- never guessed at as ANY_SUPERVISOR or UNASSIGNED from assigned_to alone, which
	 * is exactly the ambiguity issue #27 reports. This genuinely exercises a pre-V6 row: flyway.clean()
	 * plus a fresh Flyway instance targeted at version 5 leaves the database at the schema V6 has not
	 * touched yet (no assignment column, no NOT VALID constraints), so the raw INSERT below is not
	 * running against a database where a constraint could reject it -- it is exactly the shape a row
	 * written by the pre-issue-#27 code would have had. flyway.migrate() afterwards brings the schema
	 * up to V6 (ADD COLUMN ... NULL, non-validating constraints), which never touches this already
	 * -inserted row, and then applies the repeatable seed. Reading it afterwards through
	 * OrderRepository's normal COALESCE mapping is what proves the read path, not a synthetic
	 * assertion against a hand-built SQL string.
	 */
	@Test
	void preV6RowWithNoAssignedToReadsAsNotRecorded() throws Exception {
		migrateOnlyToVersion5();

		insertRawStaff("old-trader");
		long fundId = insertRawFund("OLD1");
		long securityId = insertRawSecurity("OLDA");
		long orderId = insertRawOrder("pre-v6-a", fundId, securityId, "old-trader");
		insertRawQuarantineWithNoAssignmentColumn(orderId, null);

		flyway.migrate();

		OrderView view = readView(getOrder(orderId, "sup-1"));
		assertThat(view.quarantine()).isNotNull();
		assertThat(view.quarantine().assignment()).isEqualTo("NOT_RECORDED");
		assertThat(view.quarantine().assignedTo()).isNull();
	}

	/**
	 * Same pre-V6 shape as above, but assigned_to was set on the old row (an old escalation actually
	 * assigned someone) -- the read path maps that to ASSIGNED, since a named assignee unambiguously
	 * means the quarantine was escalated to them, even without the newer column recording it in so
	 * many words.
	 */
	@Test
	void preV6RowWithAssignedToReadsAsAssigned() throws Exception {
		migrateOnlyToVersion5();

		insertRawStaff("old-trader");
		insertRawStaff("old-assignee");
		long fundId = insertRawFund("OLD2");
		long securityId = insertRawSecurity("OLDB");
		long orderId = insertRawOrder("pre-v6-b", fundId, securityId, "old-trader");
		insertRawQuarantineWithNoAssignmentColumn(orderId, "old-assignee");

		flyway.migrate();

		OrderView view = readView(getOrder(orderId, "sup-1"));
		assertThat(view.quarantine()).isNotNull();
		assertThat(view.quarantine().assignment()).isEqualTo("ASSIGNED");
		assertThat(view.quarantine().assignedTo()).isEqualTo("old-assignee");
	}

	/**
	 * A NOT VALID constraint still applies to every new INSERT (only pre-existing rows are exempt), so
	 * a raw insert of a brand-new quarantine row with assignment left NULL must be rejected by
	 * quarantine_assignment_required -- proving the migration does not just add a column but actually
	 * closes the gap for anything written from now on.
	 */
	@Test
	void newRowWithNoAssignmentIsRejectedByTheDatabase() throws Exception {
		OrderView passed = readView(submit("constraint-a", "anne", "BUY", "KSTL", 1_000L));
		assertThat(passed.status()).isEqualTo("PASS");

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO quarantine (order_id, reason, matched_order_id, quarantined_at, expires_at, assignment, assigned_to)
				VALUES (?, 'SENDER_OUT_OF_OFFICE', NULL, ?, ?, NULL, NULL)
				""", passed.id(), Timestamp.from(FIXED_START), Timestamp.from(FIXED_START.plusSeconds(1800))))
			.as("quarantine_assignment_required (NOT VALID) must still reject a new row's NULL assignment")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * Same idea for the other new-row constraint: assignment=ASSIGNED with no assignee named
	 * contradicts quarantine_assignment_matches_assignee.
	 */
	@Test
	void newRowWithAssignedStateButNoAssigneeIsRejectedByTheDatabase() throws Exception {
		OrderView passed = readView(submit("constraint-b", "anne", "BUY", "KSTL", 1_000L));
		assertThat(passed.status()).isEqualTo("PASS");

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO quarantine (order_id, reason, matched_order_id, quarantined_at, expires_at, assignment, assigned_to)
				VALUES (?, 'SENDER_OUT_OF_OFFICE', NULL, ?, ?, 'ASSIGNED', NULL)
				""", passed.id(), Timestamp.from(FIXED_START), Timestamp.from(FIXED_START.plusSeconds(1800))))
			.as("quarantine_assignment_matches_assignee (NOT VALID) must reject ASSIGNED with no assignee")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- helpers ----

	/**
	 * Leaves the database at V5 (staff/fund/security/trade_order/quarantine exist, no assignment
	 * column, no V6 constraints, no repeatable seed) so a raw INSERT below has exactly the shape a
	 * row written before issue #27's migration would have had. Uses classpath:db/migration only
	 * (never classpath:db/seed), so the seeded R__seed.sql staff/fund/security are deliberately not
	 * present yet -- this test inserts its own minimal fixture instead of relying on it.
	 */
	private void migrateOnlyToVersion5() {
		flyway.clean();
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion("5"))
			.load()
			.migrate();
	}

	private void insertRawStaff(String id) {
		jdbcTemplate.update(
				"INSERT INTO staff (id, name, team, backup_staff_id, out_of_office_from, out_of_office_until, role) "
						+ "VALUES (?, ?, 'desk-x', NULL, NULL, NULL, 'TRADER')",
				id, id);
	}

	private long insertRawFund(String code) {
		jdbcTemplate.update(
				"INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, 1000000.0000, 100000.0000, true)",
				code, code + " fixture fund");
		return jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, code);
	}

	private long insertRawSecurity(String ticker) {
		jdbcTemplate.update("""
				INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
				VALUES (?, ?, ?, 10.0000, 1000000, 100000)
				""", ticker, ticker, ticker);
		return jdbcTemplate.queryForObject("SELECT id FROM security WHERE ticker = ?", Long.class, ticker);
	}

	private long insertRawOrder(String clientOrderId, long fundId, long securityId, String submittedBy) {
		jdbcTemplate.update("""
				INSERT INTO trade_order (client_order_id, fund_id, security_id, side, quantity, reference_price,
				    submitted_by, submitted_at, request_hash)
				VALUES (?, ?, ?, 'BUY', 100, 10.0000, ?, ?, ?)
				""", clientOrderId, fundId, securityId, submittedBy, Timestamp.from(FIXED_START), "raw-hash-" + clientOrderId);
		return jdbcTemplate.queryForObject("SELECT id FROM trade_order WHERE client_order_id = ?", Long.class,
				clientOrderId);
	}

	/**
	 * No "assignment" column reference: at V5 that column does not exist yet. Also writes the
	 * QUARANTINED order_event a real quarantine always carries -- OrderService.deriveStatus requires
	 * at least one event to exist for any order, real or raw.
	 */
	private void insertRawQuarantineWithNoAssignmentColumn(long orderId, String assignedTo) {
		jdbcTemplate.update(
				"INSERT INTO order_event (order_id, event_type, actor, occurred_at, detail) VALUES (?, 'QUARANTINED', 'old-trader', ?, '{}')",
				orderId, Timestamp.from(FIXED_START));
		jdbcTemplate.update("""
				INSERT INTO quarantine (order_id, reason, matched_order_id, quarantined_at, expires_at, assigned_to)
				VALUES (?, 'SENDER_OUT_OF_OFFICE', NULL, ?, ?, ?)
				""", orderId, Timestamp.from(FIXED_START), Timestamp.from(FIXED_START.plusSeconds(1800)), assignedTo);
	}

	private void markOutOfOffice(String staffId) {
		jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = ?",
				Timestamp.from(OOO_FROM), Timestamp.from(OOO_UNTIL), staffId);
	}

	private void clearOutOfOffice(String staffId) {
		jdbcTemplate.update("UPDATE staff SET out_of_office_from = NULL, out_of_office_until = NULL WHERE id = ?",
				staffId);
	}

	private ResponseEntity<String> submit(String clientOrderId, String actor, String side, String ticker,
			long quantity) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", clientOrderId);
		body.put("fundId", hgfFundId);
		body.put("side", side);
		body.put("ticker", ticker);
		body.put("quantity", quantity);
		return restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token(actor, "TRADER")))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private ResponseEntity<String> getOrder(long orderId, String actor) throws Exception {
		return restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + orderId)
			.headers(h -> h.setBearerAuth(token(actor, "SUPERVISOR")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
	}

	private String token(String staffId, String role) {
		try {
			return TokenTool.signedToken(staffId, java.util.List.of(role), 5, TEST_SECRET);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private OrderView readView(ResponseEntity<String> response) throws Exception {
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("expected a successful response, got %s: %s", response.getStatusCode(), response.getBody())
			.isTrue();
		return objectMapper.readValue(response.getBody(), OrderView.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(FIXED_START);
		}

	}

}
