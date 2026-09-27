package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.cucumber.java.en.Given;

import io.github.ciaran11221.compliance.scenario.FixtureLoader;
import io.github.ciaran11221.compliance.scenario.Scenario;
import io.github.ciaran11221.compliance.support.MutableClock;

/**
 * "the demo data" and "fund CODE is set up as in fixture NAME" (M10 part 2, issue #31): the only
 * two steps that change database state wholesale rather than checking or acting on it. Reads the
 * SAME fixtures/fund-state/*.yaml files S001-S005 resolve through fixture: (FixtureLoader,
 * scenario.Scenario.Given) -- one fixture shape for both runners, per the issue's own falsifier.
 *
 * <p>
 * Applying a fixture: sets the fund's total assets/cash/diversified flag (updating the seeded row
 * if the code already exists -- HGF/WVF/CBF do, from R__seed.sql -- inserting otherwise);
 * upserts each security's price/voting shares/ADV/issuer; replaces the fund's holdings outright
 * (delete then insert, so a fixture with an empty holdings: actually clears them); sets the
 * restricted flag of every security the fixture itself lists (never securities outside it,
 * so applying a ZPHR-only fixture never touches KSTL's unrelated restricted status); applies any
 * settings: as a new setting_value row (none of today's five fixtures use this, but a future one
 * can); and submits each pendingOrders: entry as a real order over the HTTP API, signed by the
 * seeded trader "anne" -- the most honest way to create "pending" exposure (OrderService's own
 * findPendingOrders), since it runs the exact same intake path, quarantine check and per-fund lock
 * a real trader's order would.
 */
public class FixtureSteps {

	@Autowired
	private Flyway flyway;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@Given("the demo data")
	public void theDemoData() {
		CucumberSupport.resetDatabase(flyway, clock);
	}

	@Given("fund {word} is set up as in fixture {string}")
	public void fundIsSetUpAsInFixture(String code, String fixtureName) throws Exception {
		Scenario.Given given = FixtureLoader.load(fixtureName);
		if (!given.fund().code().equals(code)) {
			throw new IllegalStateException("fixture \"" + fixtureName + "\" is for fund " + given.fund().code()
					+ ", not " + code + " named in the step");
		}
		applyFixture(given);
	}

	private void applyFixture(Scenario.Given given) throws Exception {
		Scenario.Fund fund = given.fund();
		int updated = jdbcTemplate.update("UPDATE fund SET total_assets = ?, cash = ?, diversified = ? WHERE code = ?",
				fund.totalAssets(), fund.cash(), fund.diversified(), fund.code());
		if (updated == 0) {
			jdbcTemplate.update("INSERT INTO fund (code, name, total_assets, cash, diversified) VALUES (?, ?, ?, ?, ?)",
					fund.code(), fund.code() + " fixture fund", fund.totalAssets(), fund.cash(), fund.diversified());
		}
		long fundId = CucumberSupport.fundId(jdbcTemplate, fund.code());

		List<Scenario.Security> securities = given.securities() != null ? given.securities() : List.of();
		for (Scenario.Security security : securities) {
			String issuer = security.issuer() != null ? security.issuer() : security.ticker();
			int securityUpdated = jdbcTemplate.update("""
					UPDATE security SET price = ?, voting_shares_outstanding = ?, avg_daily_volume = ?, issuer_name = ?
					WHERE ticker = ?
					""", security.price(), security.votingSharesOutstanding(), security.avgDailyVolume(), issuer,
					security.ticker());
			if (securityUpdated == 0) {
				jdbcTemplate.update("""
						INSERT INTO security (ticker, name, issuer_name, price, voting_shares_outstanding, avg_daily_volume)
						VALUES (?, ?, ?, ?, ?, ?)
						""", security.ticker(), security.ticker(), issuer, security.price(),
						security.votingSharesOutstanding(), security.avgDailyVolume());
			}
		}

		jdbcTemplate.update("DELETE FROM holding WHERE fund_id = ?", fundId);
		List<Scenario.Holding> holdings = given.holdings() != null ? given.holdings() : List.of();
		for (Scenario.Holding holding : holdings) {
			jdbcTemplate.update(
					"INSERT INTO holding (fund_id, security_id, quantity) SELECT ?, id, ? FROM security WHERE ticker = ?",
					fundId, holding.quantity(), holding.ticker());
		}

		Set<String> restrictedTickers = new HashSet<>(given.restricted() != null ? given.restricted() : List.of());
		for (Scenario.Security security : securities) {
			if (restrictedTickers.contains(security.ticker())) {
				jdbcTemplate.update("""
						INSERT INTO restricted_security (security_id, reason, added_at)
						SELECT id, 'fixture', ? FROM security WHERE ticker = ?
						ON CONFLICT (security_id) DO NOTHING
						""", Timestamp.from(clock.instant()), security.ticker());
			}
			else {
				jdbcTemplate.update(
						"DELETE FROM restricted_security WHERE security_id = (SELECT id FROM security WHERE ticker = ?)",
						security.ticker());
			}
		}

		if (given.settings() != null && !given.settings().isEmpty()) {
			Instant now = clock.instant();
			given.settings().forEach((key, value) -> jdbcTemplate.update("""
					INSERT INTO setting_value (setting_key, value, active_from, change_request_id)
					VALUES (?, ?, ?, NULL)
					""", key, new BigDecimal(value.toString()), Timestamp.from(now)));
		}

		List<Scenario.Order> pendingOrders = given.pendingOrders() != null ? given.pendingOrders() : List.of();
		for (Scenario.Order pending : pendingOrders) {
			submitFixturePendingOrder(fundId, pending);
		}
	}

	/**
	 * Submits a fixture's pendingOrders: entry as a real order, signed by the seeded trader "anne",
	 * over the same POST /api/orders every When step uses -- OrderService's own findPendingOrders
	 * counts any order that is not yet FILLED or CANCELLED as pending exposure, whatever its own
	 * decision outcome, so this is exactly how a real pending buy would come to exist.
	 */
	private void submitFixturePendingOrder(long fundId, Scenario.Order pending) throws Exception {
		String token = CucumberSupport.token("anne", "TRADER");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", "fixture-" + fundId + "-" + pending.ticker() + "-" + pending.side());
		body.put("fundId", fundId);
		body.put("side", pending.side());
		body.put("ticker", pending.ticker());
		body.put("quantity", pending.quantity());

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		if (response.getStatusCode().value() != 201) {
			throw new IllegalStateException("fixture's pending " + pending.side() + " of " + pending.ticker()
					+ " for fund id " + fundId + " was rejected: " + response.getStatusCode() + " " + response.getBody());
		}
	}

}
