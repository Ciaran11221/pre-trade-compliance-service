package io.github.ciaran11221.compliance.cucumber;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import io.cucumber.java.en.When;

/**
 * "&lt;staff&gt; tries to release &lt;staff&gt;'s order as a &lt;ROLE&gt;" and "&lt;staff&gt; releases &lt;staff&gt;'s
 * order" (M10 part 2, issue #31, S026): both POST /api/quarantine/{id}/release with a token for the
 * acting staff member. "tries to release ... as a ROLE" signs that explicit role (S026 tests
 * brian, a seeded TRADER, attempting a SUPERVISOR-shaped release of his own order -- the sender
 * check must refuse this regardless of the role claimed); the plain "releases" step signs the
 * actor's own seeded role instead.
 */
public class SecondPersonSteps {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ScenarioState state;

	@LocalServerPort
	private int port;

	private final RestClient restClient = RestClient.create();

	@When("{staff} tries to release {staff}'s order as a {word}")
	public void staffTriesToReleaseAs(String actor, String targetStaff, String role) {
		release(actor, targetStaff, role);
	}

	@When("{staff} releases {staff}'s order")
	public void staffReleases(String actor, String targetStaff) {
		release(actor, targetStaff, CucumberSupport.roleOf(jdbcTemplate, actor));
	}

	private void release(String actor, String targetStaff, String role) {
		long orderId = state.lastOrderIdFor(targetStaff);
		String token = CucumberSupport.token(actor, role);
		ResponseEntity<String> response = restClient.post()
			.uri("http://localhost:" + port + "/api/quarantine/" + orderId + "/release")
			.headers(h -> h.setBearerAuth(token))
			.exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
				.headers(res.getHeaders())
				.body(res.bodyTo(String.class)));
		state.setLastResponse(response);
	}

}
