package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.cucumber.java.en.When;

import io.github.ciaran11221.compliance.orders.OrderView;

/**
 * "&lt;staff&gt; buys/sells $&lt;amount&gt; of &lt;TICKER&gt; for fund &lt;CODE&gt;" and "&lt;staff&gt; buys &lt;n&gt;
 * shares of &lt;TICKER&gt; for fund &lt;CODE&gt;" (M10 part 2, issue #31): every order goes over the real
 * HTTP API (POST /api/orders), signed with the actor's own seeded role -- their "default role" per
 * the issue -- read from the staff table, never assumed. A money amount's quantity = amount /
 * price, which must divide exactly; this fails the step (not the eventual order-size or cash rule)
 * when it does not.
 */
public class OrderSteps {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ScenarioState state;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@When("{staff} buys {money} of {word} for fund {word}")
	public void staffBuysMoneyOf(String staff, BigDecimal amount, String ticker, String fundCode) throws Exception {
		submitByAmount(staff, "BUY", amount, ticker, fundCode);
	}

	@When("{staff} sells {money} of {word} for fund {word}")
	public void staffSellsMoneyOf(String staff, BigDecimal amount, String ticker, String fundCode) throws Exception {
		submitByAmount(staff, "SELL", amount, ticker, fundCode);
	}

	@When("{staff} buys {shareCount} shares of {word} for fund {word}")
	public void staffBuysShares(String staff, long shares, String ticker, String fundCode) throws Exception {
		submit(staff, "BUY", ticker, fundCode, shares);
	}

	private void submitByAmount(String staff, String side, BigDecimal amount, String ticker, String fundCode)
			throws Exception {
		BigDecimal price = jdbcTemplate.queryForObject("SELECT price FROM security WHERE ticker = ?", BigDecimal.class,
				ticker);
		BigDecimal[] divRem = amount.divideAndRemainder(price);
		if (divRem[1].compareTo(BigDecimal.ZERO) != 0) {
			throw new IllegalStateException(
					amount + " does not divide exactly by " + ticker + "'s price " + price);
		}
		submit(staff, side, ticker, fundCode, divRem[0].longValueExact());
	}

	private void submit(String staff, String side, String ticker, String fundCode, long quantity) throws Exception {
		long fundId = CucumberSupport.fundId(jdbcTemplate, fundCode);
		String token = CucumberSupport.token(staff, CucumberSupport.roleOf(jdbcTemplate, staff));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientOrderId", state.nextClientOrderId(staff));
		body.put("fundId", fundId);
		body.put("side", side);
		body.put("ticker", ticker);
		body.put("quantity", quantity);

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		state.setLastResponse(response);
		if (response.getStatusCode().value() == 201) {
			OrderView view = objectMapper.readValue(response.getBody(), OrderView.class);
			state.recordOrder(staff, view.id());
		}
	}

}
