package io.github.ciaran11221.compliance.cucumber;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import io.github.ciaran11221.compliance.limits.LimitChangeView;
import io.github.ciaran11221.compliance.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limit-change steps (M10 part 2, issue #31, S015): request, required-approvals preview,
 * approve, read back status, and move the shared MutableClock forward without sleeping. Every
 * "hours/minutes pass" step ADDS to the clock's current instant (never resets to a fixed offset
 * from the start), so "23 hours and 59 minutes pass" followed by "1 minute passes" lands at
 * exactly 24 hours after the final approval, the way the two Gherkin lines read.
 */
public class LimitChangeSteps {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MutableClock clock;

	@Autowired
	private ScenarioState state;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@When("{staff} asks to change {word} to {bigdecimal}")
	public void staffAsksToChange(String staff, String setting, BigDecimal newValue) throws Exception {
		String token = CucumberSupport.token(staff, CucumberSupport.roleOf(jdbcTemplate, staff));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("key", setting);
		body.put("newValue", newValue);
		body.put("reason", "cucumber scenario");

		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.headers(h -> h.setBearerAuth(token))
			.body(objectMapper.writeValueAsString(body))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));

		state.setLastResponse(response);
		// A plain verb ("asks", "approves", "buys") expects success; a refusal gets its own
		// "tries to ..." sentence, as release does. Failing here stops a later step reading stale state.
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("%s's request to change %s to %s: %s", staff, setting, newValue, response.getBody())
			.isTrue();
		LimitChangeView view = objectMapper.readValue(response.getBody(), LimitChangeView.class);
		state.setCurrentChangeId(view.id());
	}

	@Then("the change needs {int} approvals")
	public void theChangeNeedsApprovals(int approvals) throws Exception {
		LimitChangeView view = getChange();
		assertThat(view.preview().requiredApprovals()).as("required approvals").isEqualTo(approvals);
	}

	@When("{staff} approves the change")
	public void staffApprovesTheChange(String staff) throws Exception {
		String token = CucumberSupport.token(staff, CucumberSupport.roleOf(jdbcTemplate, staff));
		long id = state.currentChangeId();
		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/limit-changes/" + id + "/approvals")
			.headers(h -> h.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		state.setLastResponse(response);
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("%s's approval of change %d: %s", staff, id, response.getBody())
			.isTrue();
	}

	@Then("the change is {word}")
	public void theChangeIs(String status) throws Exception {
		LimitChangeView view = getChange();
		assertThat(view.status()).as("limit change %d status", state.currentChangeId()).isEqualTo(status);
	}

	@Then("the active value of {word} is {bigdecimal}")
	public void theActiveValueIs(String setting, BigDecimal expected) throws Exception {
		assertActiveLimit(restClient, objectMapper, port, setting, expected);
	}

	@When("{int} hours and {int} minutes pass")
	public void hoursAndMinutesPass(int hours, int minutes) {
		clock.set(clock.instant().plus(Duration.ofHours(hours)).plus(Duration.ofMinutes(minutes)));
	}

	@When("{int} minute passes")
	@When("{int} minutes pass")
	public void minutesPass(int minutes) {
		clock.set(clock.instant().plus(Duration.ofMinutes(minutes)));
	}

	private LimitChangeView getChange() throws Exception {
		long id = state.currentChangeId();
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/limit-changes/" + id)
			.headers(h -> h.setBearerAuth(CucumberSupport.token("comp-1", "COMPLIANCE")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("GET limit change %d: %s", id, response.getBody())
			.isTrue();
		return objectMapper.readValue(response.getBody(), LimitChangeView.class);
	}

	/** Shared with FactSteps' "SETTING is currently VALUE" (the same fact, read as a Given before
	 * any change, or as a Then after one activates). Reads the real GET /api/limits, never a value
	 * a step itself computed. */
	@SuppressWarnings("unchecked")
	static void assertActiveLimit(RestClient restClient, ObjectMapper objectMapper, int port, String setting,
			BigDecimal expected) throws Exception {
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + port + "/api/limits")
			.headers(h -> h.setBearerAuth(CucumberSupport.token("anne", "TRADER")))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		assertThat(response.getStatusCode().is2xxSuccessful())
			.as("GET /api/limits: %s", response.getBody())
			.isTrue();
		Map<String, Object> limits = objectMapper.readValue(response.getBody(), Map.class);
		Object raw = limits.get(setting);
		assertThat(raw).as("no \"%s\" entry in GET /api/limits", setting).isNotNull();
		assertThat(new BigDecimal(String.valueOf(raw))).as("active value of %s", setting).isEqualByComparingTo(expected);
	}

}
