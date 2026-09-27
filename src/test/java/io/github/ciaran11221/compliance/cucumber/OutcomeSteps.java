package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.cucumber.java.en.Then;

import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.orders.RuleResultView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outcome Then step (M10 part 2, issue #31): each one re-reads the stored order over GET
 * /api/orders/{id} -- the real security chain, not a value a When step already computed for itself
 * -- so a Then here can only pass if the server actually decided, quarantined, or refused what the
 * sentence claims.
 */
public class OutcomeSteps {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ScenarioState state;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@Then("the order is {word}")
	public void theOrderIs(String status) throws Exception {
		assertOrderStatus(state.lastOrderId(), status);
	}

	@Then("{staff}'s order is {word}")
	public void staffsOrderIs(String staff, String status) throws Exception {
		assertOrderStatus(state.lastOrderIdFor(staff), status);
	}

	@Then("{staff}'s order is still QUARANTINED")
	public void staffsOrderIsStillQuarantined(String staff) throws Exception {
		assertOrderStatus(state.lastOrderIdFor(staff), "QUARANTINED");
	}

	@Then("the {word} check is {word}")
	public void theRuleCheckIs(String ruleName, String outcome) throws Exception {
		OrderView view = getOrder(state.lastOrderId());
		RuleResultView result = ruleResult(view, ruleName);
		assertThat(result.outcome()).as("%s check on order %d", ruleName, view.id()).isEqualTo(outcome);
	}

	@Then("the over-5% group is {pct} of the fund")
	public void theOverFivePercentGroupIs(BigDecimal pct) throws Exception {
		OrderView view = getOrder(state.lastOrderId());
		RuleResultView diversification = ruleResult(view, "diversification");
		assertThat(diversification.measuredValue()).as("over-5%% group pct on order %d", view.id())
			.isEqualByComparingTo(pct);
	}

	@Then("{staff}'s order is held as a possible duplicate")
	public void staffsOrderIsHeldAsAPossibleDuplicate(String staff) throws Exception {
		OrderView view = getOrder(state.lastOrderIdFor(staff));
		assertThat(view.quarantine()).as("quarantine detail for %s's order %d", staff, view.id()).isNotNull();
		assertThat(view.quarantine().reason()).as("quarantine reason for %s's order %d", staff, view.id())
			.isEqualTo("POSSIBLE_DUPLICATE");
	}

	@Then("the request is refused with status {int}")
	public void theRequestIsRefusedWithStatus(int code) {
		assertThat(state.lastResponse().getStatusCode().value()).as("last response status").isEqualTo(code);
	}

	@Then("the refusal says {string}")
	public void theRefusalSays(String text) throws Exception {
		String body = state.lastResponse().getBody();
		JsonNode json = objectMapper.readTree(body);
		String detail = json.has("detail") ? json.get("detail").asText() : "";
		assertThat(detail).as("problem detail: %s", body).contains(text);
	}

	private RuleResultView ruleResult(OrderView view, String ruleName) {
		assertThat(view.decision()).as("order %d has no decision yet (still quarantined?)", view.id()).isNotNull();
		return view.decision()
			.ruleResults()
			.stream()
			.filter(r -> r.ruleName().equals(ruleName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no rule result named \"" + ruleName + "\" on order " + view.id()));
	}

	private void assertOrderStatus(long orderId, String expectedStatus) throws Exception {
		OrderView view = getOrder(orderId);
		assertThat(view.status()).as("status of order %d", orderId).isEqualTo(expectedStatus);
	}

	private OrderView getOrder(long orderId) throws Exception {
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/orders/" + orderId)
			.headers(h -> h.setBearerAuth(CucumberSupport.token("anne", "TRADER")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("GET order %d: %s", orderId, response.getBody())
			.isTrue();
		return objectMapper.readValue(response.getBody(), OrderView.class);
	}

}
