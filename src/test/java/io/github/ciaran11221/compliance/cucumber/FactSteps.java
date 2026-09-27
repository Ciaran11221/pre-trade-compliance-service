package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.cucumber.java.en.Given;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every "fact" Given sentence (M10 part 2, issue #31): each one reads the database (or, for a
 * setting, the real GET /api/limits) and fails if the sentence is false, rather than merely
 * restating what a fixture step already claimed -- the falsifier issue #31 itself names ("a step
 * that always passes makes the test decoration"). Every money comparison is exact BigDecimal
 * (compareTo, never a double); every percentage comparison multiplies out rather than dividing.
 */
public class FactSteps {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private static final BigDecimal FIVE = new BigDecimal("5");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@Given("fund {word} has total assets of {money}")
	public void fundHasTotalAssets(String code, BigDecimal amount) {
		BigDecimal actual = jdbcTemplate.queryForObject("SELECT total_assets FROM fund WHERE code = ?", BigDecimal.class,
				code);
		assertThat(actual).as("fund %s total assets", code).isEqualByComparingTo(amount);
	}

	@Given("fund {word} has {money} of cash")
	public void fundHasCash(String code, BigDecimal amount) {
		BigDecimal actual = jdbcTemplate.queryForObject("SELECT cash FROM fund WHERE code = ?", BigDecimal.class, code);
		assertThat(actual).as("fund %s cash", code).isEqualByComparingTo(amount);
	}

	@Given("fund {word} holds {money} of {word}")
	public void fundHoldsMoneyOf(String code, BigDecimal amount, String ticker) {
		BigDecimal value = holdingValue(code, ticker);
		assertThat(value).as("value of fund %s's %s holding", code, ticker).isEqualByComparingTo(amount);
	}

	@Given("fund {word} holds {pct} of its assets in {word}")
	public void fundHoldsPctOfAssetsIn(String code, BigDecimal pct, String ticker) {
		BigDecimal value = holdingValue(code, ticker);
		BigDecimal totalAssets = totalAssets(code);
		// value x 100 == pct x total assets, exact -- never divide.
		assertThat(value.multiply(HUNDRED)).as("fund %s's %s holding as a pct of total assets", code, ticker)
			.isEqualByComparingTo(pct.multiply(totalAssets));
	}

	/**
	 * Sum of the fund's OWN position values that are each strictly over 5% of total assets (value x
	 * 100 > 5 x assets, never divide) -- "5%" is fixed text in this one sentence, not a parameter,
	 * matching part 1's template exactly.
	 */
	@Given("fund {word} already has {money} in positions over 5%")
	public void fundAlreadyHasInPositionsOverFivePercent(String code, BigDecimal amount) {
		BigDecimal totalAssets = totalAssets(code);
		List<BigDecimal> values = jdbcTemplate.queryForList("""
				SELECT h.quantity * s.price
				FROM holding h JOIN security s ON s.id = h.security_id JOIN fund f ON f.id = h.fund_id
				WHERE f.code = ?
				""", BigDecimal.class, code);
		BigDecimal sum = BigDecimal.ZERO;
		for (BigDecimal value : values) {
			if (value.multiply(HUNDRED).compareTo(FIVE.multiply(totalAssets)) > 0) {
				sum = sum.add(value);
			}
		}
		assertThat(sum).as("fund %s's positions strictly over 5%% of assets", code).isEqualByComparingTo(amount);
	}

	@Given("{word} is on the restricted list")
	public void tickerIsOnTheRestrictedList(String ticker) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM restricted_security rs JOIN security s ON s.id = rs.security_id WHERE s.ticker = ?",
				Integer.class, ticker);
		assertThat(count).as("%s is on the restricted list", ticker).isGreaterThan(0);
	}

	/** Sum of every not-yet-filled-or-cancelled BUY of this ticker for this fund -- OrderService's
	 * own "pending exposure" reading (OrderRepository.findPendingOrders), not merely what a fixture
	 * step claims it submitted. */
	@Given("fund {word} has a pending buy of {money} of {word}")
	public void fundHasAPendingBuyOf(String code, BigDecimal amount, String ticker) {
		List<BigDecimal> values = jdbcTemplate.queryForList("""
				SELECT o.quantity * o.reference_price
				FROM trade_order o JOIN fund f ON f.id = o.fund_id JOIN security s ON s.id = o.security_id
				WHERE f.code = ? AND s.ticker = ? AND o.side = 'BUY'
				  AND NOT EXISTS (
				      SELECT 1 FROM order_event e WHERE e.order_id = o.id AND e.event_type IN ('FILLED', 'CANCELLED')
				  )
				""", BigDecimal.class, code, ticker);
		BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(sum).as("pending BUY exposure to %s for fund %s", ticker, code).isEqualByComparingTo(amount);
	}

	@Given("{word} is currently {bigdecimal}")
	public void settingIsCurrently(String setting, BigDecimal expected) throws Exception {
		LimitChangeSteps.assertActiveLimit(restClient, objectMapper, port, setting, expected);
	}

	private BigDecimal totalAssets(String code) {
		return jdbcTemplate.queryForObject("SELECT total_assets FROM fund WHERE code = ?", BigDecimal.class, code);
	}

	/** COALESCEs to zero rather than throwing when the fund holds none of this ticker at all, so a
	 * false "holds $0" fact fails the assertion instead of blowing up with no result row. */
	private BigDecimal holdingValue(String code, String ticker) {
		return jdbcTemplate.queryForObject("""
				SELECT COALESCE(h.quantity, 0) * s.price
				FROM security s
				LEFT JOIN holding h ON h.security_id = s.id
				  AND h.fund_id = (SELECT id FROM fund WHERE code = ?)
				WHERE s.ticker = ?
				""", BigDecimal.class, code, ticker);
	}

}
