package io.github.ciaran11221.compliance.quarantine;

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

import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.scenario.Scenario;
import io.github.ciaran11221.compliance.scenario.ScenarioLoader;
import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;
import io.github.ciaran11221.compliance.support.TokenTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every "kind: quarantine" scenario in src/test/resources/scenarios/, over the real HTTP API
 * with real signed tokens and the real Testcontainers Postgres -- the same pattern
 * LimitChangeScenarioTest uses (see its Javadoc for why Flyway.clean()+migrate() is this corpus's
 * reset between scenarios: the audit tables involved are insert-only). ScenarioRunnerCoverageTest
 * fails the build the moment this class stops registering a runner for the "quarantine" kind;
 * quarantineScenarios() itself fails if the corpus filter ever finds zero scenarios.
 *
 * <p>
 * Every scenario here reuses the seeded R__seed.sql fund/staff (HGF, KSTL, anne/brian/sup-1/sup-2
 * /sup-3/comp-1/exec-1) UNLESS its given.fund is set, in which case seedFixture (the same shape
 * LimitChangeScenarioTest's own seedFixture uses) inserts a scenario-specific fund/securities
 * instead -- needed by the "counts-as-pending" scenario, which wants diversified=false to isolate
 * the cash rule the same way orders.PendingExposureAndCashRaceTest does.
 *
 * <p>
 * Step actions: "submit-order" (fields: actor/roles implicit via step.actor()/roles(), side,
 * ticker, quantity, ref) POSTs to /api/orders with a clientOrderId derived as
 * "<scenario id>-<ref>" (so scenarios never need to spell one out) and stores the returned order
 * id under ref; "release"/"reject" (fields: ref) POST to /api/quarantine/{id}/release|reject;
 * "get-order" (fields: ref) is a side-effect-free GET /api/orders/{id}; "advance-clock" (fields:
 * minutes) moves the shared MutableClock forward from FIXED_START, no sleep.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfig.class, QuarantineScenarioTest.ClockOverride.class })
@ActiveProfiles("test")
class QuarantineScenarioTest {

	// Must match application-test.yml's compliance.security.jwt-secret exactly.
	private static final String TEST_SECRET = "test-only-secret-9f3c2b7a1e6d4508b0c7d2a4e9f61c3b";

	static final Instant FIXED_START = Instant.parse("2026-01-01T00:00:00Z");

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

	private long defaultFundId;

	@TestFactory
	List<DynamicTest> quarantineScenarios() {
		List<Scenario> scenarios = ScenarioLoader.loadAllValidated()
			.stream()
			.filter(s -> "quarantine".equals(s.kind()))
			.toList();

		assertThat(scenarios).as("no kind: quarantine scenarios were found in the corpus").isNotEmpty();

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
		defaultFundId = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = 'HGF'", Long.class);
	}

	/** Same shape as LimitChangeScenarioTest's seedFixture; only used when given.fund is set. */
	private void seedFixture(Scenario.Given given) {
		if (given == null || given.fund() == null) {
			return;
		}
		Scenario.Fund fund = given.fund();
		jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, ?)",
				fund.code(), fund.code() + " fixture fund", fund.totalAssets(), fund.cash(), fund.diversified());
		defaultFundId = jdbcTemplate.queryForObject("SELECT id FROM fund WHERE code = ?", Long.class, fund.code());

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
	}

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
			case "submit-order" -> doSubmitOrder(scenario, step, refs);
			case "release" -> doMutation(step, refs, "release");
			case "reject" -> doMutation(step, refs, "reject");
			case "get-order" -> doGetOrder(step, refs);
			case "advance-clock" -> doAdvanceClock(step);
			default -> throw new IllegalStateException(
					"scenario " + scenario.id() + ": unknown step action \"" + step.action() + "\"");
		}
	}

	private void doSubmitOrder(Scenario scenario, Scenario.Step step, Map<String, Long> refs) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		String ref = ref(step);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", scenario.id() + "-" + ref);
		body.put("fundId", defaultFundId);
		body.put("side", step.fields().get("side"));
		body.put("ticker", step.fields().get("ticker"));
		body.put("quantity", Long.parseLong(String.valueOf(step.fields().get("quantity"))));

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		checkExpect(step, response);
		if (response.getStatusCode().is2xxSuccessful()) {
			OrderView view = objectMapper.readValue(response.getBody(), OrderView.class);
			refs.put(ref, view.id());
		}
	}

	private void doMutation(Scenario.Step step, Map<String, Long> refs, String segment) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		long id = requireRef(refs, step);
		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/quarantine/" + id + "/" + segment)
			.headers(h -> h.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		checkExpect(step, response);
	}

	private void doGetOrder(Scenario.Step step, Map<String, Long> refs) throws Exception {
		String token = TokenTool.signedToken(step.actor(), step.roles(), 5, TEST_SECRET);
		long id = requireRef(refs, step);
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + id)
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
		if (ref == null) {
			throw new IllegalStateException("step " + step.action() + " needs a \"ref\" field");
		}
		return ref.toString();
	}

	private long requireRef(Map<String, Long> refs, Scenario.Step step) {
		String ref = ref(step);
		Long id = refs.get(ref);
		assertThat(id).as("step references ref \"%s\", which no earlier submit-order step created", ref).isNotNull();
		return id;
	}

	private void checkExpect(Scenario.Step step, ResponseEntity<String> response) throws Exception {
		Scenario.Expect expect = step.expect();
		if (expect == null) {
			return;
		}
		if (expect.httpStatus() != null) {
			assertThat(response.getStatusCode().value())
				.as("http status for a \"%s\" step with fields %s: %s", step.action(), step.fields(),
						response.getBody())
				.isEqualTo(expect.httpStatus());
		}

		boolean needsView = expect.resultStatus() != null || expect.quarantineReason() != null
				|| expect.assignedTo() != null || Boolean.TRUE.equals(expect.unassigned());
		if (!needsView) {
			return;
		}
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("expected a successful response to read status/quarantine fields from a \"%s\" step, got %s: %s",
					step.action(), response.getStatusCode(), response.getBody())
			.isTrue();
		OrderView view = objectMapper.readValue(response.getBody(), OrderView.class);
		if (expect.resultStatus() != null) {
			assertThat(view.status()).as("status for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.resultStatus());
		}
		if (expect.quarantineReason() != null) {
			assertThat(view.quarantine()).as("no quarantine detail on the response to a \"%s\" step with fields %s",
					step.action(), step.fields()).isNotNull();
			assertThat(view.quarantine().reason())
				.as("quarantine reason for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.quarantineReason());
		}
		if (expect.assignedTo() != null) {
			assertThat(view.quarantine()).isNotNull();
			assertThat(view.quarantine().assignedTo())
				.as("quarantine assignee for a \"%s\" step with fields %s", step.action(), step.fields())
				.isEqualTo(expect.assignedTo());
		}
		if (Boolean.TRUE.equals(expect.unassigned())) {
			assertThat(view.quarantine()).isNotNull();
			assertThat(view.quarantine().assignedTo())
				.as("expected no assignee for a \"%s\" step with fields %s", step.action(), step.fields())
				.isNull();
		}
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
