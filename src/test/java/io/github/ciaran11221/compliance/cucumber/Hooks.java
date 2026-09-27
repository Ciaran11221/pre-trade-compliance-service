package io.github.ciaran11221.compliance.cucumber;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;

import io.cucumber.java.After;
import io.cucumber.java.Before;

import io.github.ciaran11221.compliance.support.MutableClock;

/**
 * Trap #7 (issue #31): the limit-change and quarantine audit tables are insert-only, so a
 * scenario's state (S015's QUARANTINE_EXPIRY_MINUTES change to 60, any order or quarantine row)
 * cannot be reset with a DELETE -- only Flyway.clean()+migrate(), the same reset
 * LimitChangeScenarioTest/QuarantineScenarioTest/QuarantineExpiryJobTest/
 * LimitChangeConcurrentApprovalTest already use against this shared Testcontainers database. Runs
 * before EVERY scenario and after EVERY scenario (including the last), so nothing this suite
 * writes ever leaks into whichever test class the JVM happens to run next.
 */
public class Hooks {

	private final Flyway flyway;

	private final MutableClock clock;

	@Autowired
	public Hooks(Flyway flyway, MutableClock clock) {
		this.flyway = flyway;
		this.clock = clock;
	}

	@Before
	public void resetBefore() {
		CucumberSupport.resetDatabase(flyway, clock);
	}

	@After
	public void resetAfter() {
		CucumberSupport.resetDatabase(flyway, clock);
	}

}
