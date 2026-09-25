package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps LimitKey's default values from drifting away from what V3__setting_defaults.sql actually
 * loads, by reading the seeded database rather than re-typing the ten numbers a second time.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class LimitKeyDefaultsTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void everyLimitKeyDefaultMatchesTheSeededSettingValue() {
		for (LimitKey key : LimitKey.values()) {
			BigDecimal seeded = jdbcTemplate.queryForObject(
					"SELECT value FROM setting_value WHERE setting_key = ? AND change_request_id IS NULL",
					BigDecimal.class, key.name());

			assertThat(seeded).as("seeded value of %s", key).isEqualByComparingTo(key.defaultValue());
		}
	}

}
