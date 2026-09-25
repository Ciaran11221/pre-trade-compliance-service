package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import io.github.ciaran11221.compliance.support.MutableClock;
import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With only V3's seeded rows present, every key reads as its default. Inserting a row with a
 * future active_from does not change what is active until the injected Clock passes it, proven by
 * moving a MutableClock rather than sleeping.
 */
@SpringBootTest
@Import({ TestcontainersConfig.class, LimitsRepositoryTest.ClockOverride.class })
@ActiveProfiles("test")
class LimitsRepositoryTest {

	@Autowired
	private LimitsRepository limitsRepository;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void returnsSeedDefaultsWhenOnlyV3RowsExist() {
		Limits limits = limitsRepository.activeLimits();

		for (LimitKey key : LimitKey.values()) {
			assertThat(limits.get(key)).as("active value of %s", key).isEqualByComparingTo(key.defaultValue());
		}
	}

	// This test mutates both the database (an insert-only row) and the shared MutableClock bean,
	// so it dirties the context: without this, whichever test method JUnit happens to run second
	// would see the other test's leftover state.
	@Test
	@DirtiesContext
	void aFutureDatedRowIsNotActiveUntilTheClockPassesIt() {
		Instant future = clock.instant().plusSeconds(3600);
		jdbcTemplate.update(
				"INSERT INTO setting_value (setting_key, value, active_from, change_request_id) VALUES (?, ?, ?, NULL)",
				"ISSUER_LIMIT_PCT", new BigDecimal("7"), Timestamp.from(future));

		Limits beforeActivation = limitsRepository.activeLimits();
		assertThat(beforeActivation.get(LimitKey.ISSUER_LIMIT_PCT)).isEqualByComparingTo(new BigDecimal("5"));

		clock.set(future.plusSeconds(1));

		Limits afterActivation = limitsRepository.activeLimits();
		assertThat(afterActivation.get(LimitKey.ISSUER_LIMIT_PCT)).isEqualByComparingTo(new BigDecimal("7"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockOverride {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
		}

	}

}
