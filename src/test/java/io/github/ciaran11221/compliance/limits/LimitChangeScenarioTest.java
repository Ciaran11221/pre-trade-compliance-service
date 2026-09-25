package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.scenario.Scenario;
import io.github.ciaran11221.compliance.scenario.ScenarioLoader;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every "kind: limit-change" scenario in src/test/resources/scenarios/ over the real HTTP
 * API, against the real Testcontainers Postgres, with real signed tokens (TokenTool) -- the same
 * pattern RouteAccessMatrixTest and JwtAuthenticationTest use. Each scenario is its own
 * DynamicTest; ScenarioRunnerCoverageTest fails the build the moment this class stops registering
 * a runner for the "limit-change" kind, and limitChangeScenarios() itself fails if the corpus
 * filter ever finds zero scenarios, so a runner that quietly stops running anything cannot pass.
 *
 * <p>
 * <b>Isolation.</b> The limit-change audit tables (request, preview, approval, activation,
 * cancellation, refusal) are insert-only -- a Postgres trigger rejects UPDATE and DELETE outright
 * -- so a scenario's state cannot be reset with a DELETE between scenarios the way a mutable table
 * could be. Every scenario therefore starts by calling {@code Flyway.clean()} then
 * {@code migrate()}: this drops and rebuilds the whole schema, replaying V1-V4 and the R__seed.sql
 * reference data (funds, securities, staff) from nothing. That also reverts any staff
 * out-of-office flag a previous scenario set -- staff is mutable reference data and could in
 * principle be reset with a plain UPDATE, but resetting it the same way as everything else is
 * simpler and more obviously complete than tracking every mutation a scenario might make and
 * reverting it by hand. The MutableClock is reset to FIXED_START at the same point. This is
 * heavier than row-level cleanup, but it is the only reset that is provably complete against an
 * insert-only schema, and the corpus is small (ten scenarios) so the cost stays trivial.
 * {@code spring.flyway.clean-disabled=false} in application-test.yml allows this; the property is
 * test-profile only and this suite only ever runs against a disposable Testcontainers database.
 *
 * <p>
 * <b>Step actions.</b> "request", "approve" and "cancel" call the matching HTTP endpoint as
 * step.actor() with step.roles(), signing a token the same way TokenTool's callers elsewhere do.
 * "advance-clock" moves the shared MutableClock to FIXED_START plus fields.minutes, with no
 * sleep. "get" (an addition beyond request/approve/cancel/advance-clock, since Scenario.Step.action
 * is a plain unchecked String) reads a request's current view with no side effect, which is what
 * the cooling-off scenario needs to check status without approving twice. A "ref" field scopes an
 * action to one of possibly several requests a scenario creates (see the stale and hides-breach
 * corpus); omitted, it defaults to "r1".
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, LimitChangeScenarioTest.ClockOverride.class })
@ActiveProfiles("test")
class LimitChangeScenarioTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

	private static final String DEFAULT_REF = "r1";

	@LocalServerPort
	private int port;

	@Autowired
	private Flyway flyway;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private final RestClient restClient = RestClient.create();

	@TestFactory
	List<DynamicTest> limitChangeScenarios() {
		List<Scenario> scenarios = ScenarioLoader.loadAllValidated()
			.stream()
			.filter(s -> "limit-change".equals(s.kind()))
			.toList();

		// A runner that silently finds nothing to run would still show green. This is what makes
		// that impossible: if the "limit-change" filter above ever stops matching the corpus (a
		// renamed kind, a loader change), this fails loudly instead of registering zero tests.
		assertThat(scenarios).as("no kind: limit-change scenarios were found in the corpus").isNotEmpty();

		List<DynamicTest> tests = new ArrayList<>();
		for (Scenario scenario : scenarios) {
			tests.add(DynamicTest.dynamicTest(scenario.id() + " " + scenario.title(), () -> run(scenario)));
		}
		return tests;
	}

	private void run(Scenario scenario) throws Exception {
		resetDatabase();
		seedFixture(scenario.given());
		applyOutOfOffice(scenario.given());

		Map<String, Long> refs = new HashMap<>();
		for (Scenario.Step step : scenario.steps()) {
			runStep(scenario, step, refs);
		}
	}

	private void resetDatabase() {
		flyway.clean();
		flyway.migrate();
		clock.set(FIXED_START);
	}

	/** Inserts a scenario's fixture fund/securities/holdings, reusing the "rule" scenario shape. */
	private void seedFixture(Scenario.Given given) {
		if (given == null || given.fund() == null) {
			return;
		}
		Scenario.Fund fund = given.fund();
		// Scenario.Fund/Security carry no "name" field (see S001 and friends, which never set
		// one); fund/security both require one, so a readable default is generated here rather
		// than widening the scenario schema just for this.
		jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, ?)",
				fund.code(), fund.code() + " fixture fund", fund.totalAssets(), fund.cash(), fund.diversified());

		if (given.securities() != null) {
			for (Scenario.Security security : given.securities()) {
				jdbcTemplate.update("""
						INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
						VALUES (?, ?, ?, ?, ?, ?)
						""", security.ticker(), security.ticker(),
						security.issuer() != null ? security.issuer() : security.ticker(), security.price(),
						security.votingSharesOutstanding(), security.avgDailyVolume());
			}
		}

		if (given.holdings() != null) {
			for (Scenario.Holding holding : given.holdings()) {
				jdbcTemplate.update("""
						INSERT INTO holding (fund_id, security_id, quantity)
						SELECT f.id, s.id, ? FROM fund f, security s WHERE f.code = ? AND s.ticker = ?
						""", holding.quantity(), fund.code(), holding.ticker());
			}
		}
	}

	/** Staff is mutable reference data, so this is a plain UPDATE rather than an insert. */
	private void applyOutOfOffice(Scenario.Given given) {
		if (given == null || given.outOfOffice() == null) {
			return;
		}
		for (Scenario.OutOfOffice outOfOffice : given.outOfOffice()) {
			jdbcTemplate.update("UPDATE staff SET out_of_office_from = ?, out_of_office_until = ? WHERE id = ?",
					Timestamp.from(Instant.parse(outOfOffice.from())), Timestamp.from(Instant.parse(outOfOffice.until())),
					outOfOffice.staffId());
		}
	}

	private void runStep(Scenario scenario, Scenario.Step step, Map<String, Long> refs) throws Exception {
		switch (step.action()) {
			case "request" -> doRequest(scenario, step, refs);
			case "approve" -> doMutation(step, refs, "approvals");
			case "cancel" -> doMutation(step, refs, "cancel");
			case "advance-clock" -> doAdvanceClock(step);
			case "get" -> doGet(step, refs);
			default -> throw new IllegalStateException(
					"scenario " + scenario.id() + ": unknown step action \"" + step.action() + "\"");
		}
	}

	private void doRequest(Scenario scenario, Scenario.Step step, Map<String, Long> refs) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", step.fields().get("key"));
		body.put("newValue", new BigDecimal(String.valueOf(step.fields().get("newValue"))));
		body.put("reason", String.valueOf(step.fields().getOrDefault("reason", "scenario " + scenario.id())));

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		checkExpect(step, response);

		if (response.getStatusCode().is2xxSuccessful()) {
			LimitChangeView view = objectMapper.readValue(response.getBody(), LimitChangeView.class);
			refs.put(ref(step), view.id());
		}
		else if (response.getStatusCode().value() == 422) {
			// Rule 1: a refusal is recorded even though the request itself never exists.
			assertRefusalRecorded(step);
		}
	}

	private void doMutation(Scenario.Step step, Map<String, Long> refs, String segment) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		long id = requireRef(refs, step);
		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes/" + id + "/" + segment)
			.headers(h -> h.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		checkExpect(step, response);
	}

	private void doGet(Scenario.Step step, Map<String, Long> refs) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		long id = requireRef(refs, step);
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/limit-changes/" + id)
			.headers(h -> h.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		checkExpect(step, response);
	}

	private void doAdvanceClock(Scenario.Step step) {
		long minutes = Long.parseLong(String.valueOf(step.fields().get("minutes")));
		clock.set(FIXED_START.plus(Duration.ofMinutes(minutes)));
	}

	private String ref(Scenario.Step step) {
		Object ref = step.fields().get("ref");
		return ref != null ? ref.toString() : DEFAULT_REF;
	}

	private long requireRef(Map<String, Long> refs, Scenario.Step step) {
		String ref = ref(step);
		Long id = refs.get(ref);
		assertThat(id).as("step references ref \"%s\", which no earlier request step created", ref).isNotNull();
		return id;
	}

	private void checkExpect(Scenario.Step step, ResponseEntity<String> response) throws Exception {
		Scenario.Expect expect = step.expect();
		if (expect == null) {
			return;
		}
		if (expect.httpStatus() != null) {
			assertThat(response.getStatusCode().value())
				.as("http status for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.httpStatus());
		}

		boolean needsView = expect.resultStatus() != null || expect.requiredApprovals() != null
				|| expect.hidesBreach() != null;
		if (!needsView) {
			return;
		}
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("expected a successful response to read status/requiredApprovals/hidesBreach from a \"%s\" step, got %s: %s",
					step.action(), response.getStatusCode(), response.getBody())
			.isTrue();
		LimitChangeView view = objectMapper.readValue(response.getBody(), LimitChangeView.class);
		if (expect.resultStatus() != null) {
			assertThat(view.status()).as("status for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.resultStatus());
		}
		if (expect.requiredApprovals() != null) {
			assertThat(view.preview().requiredApprovals())
				.as("requiredApprovals for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.requiredApprovals());
		}
		if (expect.hidesBreach() != null) {
			assertThat(view.preview().hidesBreach())
				.as("hidesBreach for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.hidesBreach());
		}
	}

	/** Rule 1: a refused request never reaches limit_change_request, but the attempt is recorded. */
	private void assertRefusalRecorded(Scenario.Step step) {
		String key = String.valueOf(step.fields().get("key"));
		BigDecimal requestedValue = new BigDecimal(String.valueOf(step.fields().get("newValue")));
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM limit_change_refusal
				WHERE requester = ? AND setting_key = ? AND requested_value = ?
				""", Integer.class, step.actor(), key, requestedValue);
		assertThat(count)
			.as("no limit_change_refusal row recorded for %s attempting %s = %s", step.actor(), key, requestedValue)
			.isGreaterThan(0);
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
