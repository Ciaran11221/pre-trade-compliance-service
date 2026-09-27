package io.github.ciaran11221.compliance.cucumber;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;

import io.cucumber.java.Before;

/**
 * Per-scenario mutable state shared across every step-definition class in this package, via
 * constructor or field injection -- cucumber-spring gives this class a fresh instance scoped to
 * exactly one scenario (its "cucumber-glue" scope), the same instance every other glue class in
 * that scenario sees when it asks Spring for one. That is what keeps this state scenario-scoped
 * rather than a static field (M10, issue #31).
 *
 * <p>
 * The empty reset() hook exists only so cucumber-spring's SpringFactory recognises this class as
 * glue to manage in that scope at all -- a plain class with no step/hook annotation of its own is
 * never added to the per-scenario Spring context. A fresh instance already starts empty, so the
 * hook body has nothing to do.
 */
public class ScenarioState {

	private final Map<String, Long> lastOrderIdByActor = new HashMap<>();

	private int orderCounter;

	private Long lastOrderId;

	private ResponseEntity<String> lastResponse;

	private Long currentChangeId;

	@Before
	public void reset() {
		// no-op: see class Javadoc.
	}

	/** A fresh, deterministic clientOrderId for this scenario -- no wall-clock/nanoTime needed. */
	public String nextClientOrderId(String actor) {
		orderCounter++;
		return actor + "-" + orderCounter;
	}

	public void recordOrder(String actor, long orderId) {
		lastOrderIdByActor.put(actor, orderId);
		lastOrderId = orderId;
	}

	public long lastOrderId() {
		if (lastOrderId == null) {
			throw new IllegalStateException("no order has been submitted yet in this scenario");
		}
		return lastOrderId;
	}

	public long lastOrderIdFor(String actor) {
		Long id = lastOrderIdByActor.get(actor);
		if (id == null) {
			throw new IllegalStateException("no order submitted by \"" + actor + "\" yet in this scenario");
		}
		return id;
	}

	public void setLastResponse(ResponseEntity<String> response) {
		this.lastResponse = response;
	}

	public ResponseEntity<String> lastResponse() {
		if (lastResponse == null) {
			throw new IllegalStateException("no HTTP response recorded yet in this scenario");
		}
		return lastResponse;
	}

	public void setCurrentChangeId(long id) {
		this.currentChangeId = id;
	}

	public long currentChangeId() {
		if (currentChangeId == null) {
			throw new IllegalStateException("no limit change requested yet in this scenario");
		}
		return currentChangeId;
	}

}
