package io.github.ciaran11221.compliance.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Clock a test can move forward without sleeping, for the one test (LimitsRepositoryTest) that
 * needs the same Clock bean to read two different instants across a single test method. Every
 * other test in this repo needs only a fixed instant and does not need this.
 */
public class MutableClock extends Clock {

	private final AtomicReference<Instant> instant;

	private final ZoneId zone;

	public MutableClock(Instant initial) {
		this(initial, ZoneId.of("UTC"));
	}

	private MutableClock(Instant initial, ZoneId zone) {
		this.instant = new AtomicReference<>(initial);
		this.zone = zone;
	}

	public void set(Instant newInstant) {
		instant.set(newInstant);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(instant.get(), zone);
	}

	@Override
	public Instant instant() {
		return instant.get();
	}

}
