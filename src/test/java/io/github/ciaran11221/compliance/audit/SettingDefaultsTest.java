package io.github.ciaran11221.compliance.audit;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3__setting_defaults.sql must load exactly the ten firm-limit defaults,
 * with no key missing and no value drifted.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SettingDefaultsTest {

	private static final Map<String, BigDecimal> EXPECTED = Map.ofEntries(
			Map.entry("ISSUER_LIMIT_PCT", new BigDecimal("5.0000")),
			Map.entry("VOTING_LIMIT_PCT", new BigDecimal("10.0000")),
			Map.entry("OVER_LIMIT_BUCKET_PCT", new BigDecimal("25.0000")),
			Map.entry("ORDER_SIZE_ADV_PCT", new BigDecimal("10.0000")),
			Map.entry("LOOKBACK_MINUTES", new BigDecimal("5.0000")),
			Map.entry("SIMILARITY_PCT", new BigDecimal("10.0000")),
			Map.entry("QUARANTINE_EXPIRY_MINUTES", new BigDecimal("30.0000")),
			Map.entry("LARGE_LOOSENING_USD", new BigDecimal("50000000.0000")),
			Map.entry("LARGE_LOOSENING_PCT_OF_FUNDS", new BigDecimal("1.0000")),
			Map.entry("COOLING_OFF_HOURS", new BigDecimal("24.0000")));

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void allTenDefaultSettingsArePresentWithExpectedValues() {
		Map<String, BigDecimal> actual = jdbc.query(
				"SELECT setting_key, value FROM setting_value WHERE change_request_id IS NULL",
				rs -> {
					Map<String, BigDecimal> values = new java.util.HashMap<>();
					while (rs.next()) {
						values.put(rs.getString("setting_key"), rs.getBigDecimal("value"));
					}
					return values;
				});

		assertThat(actual).hasSize(EXPECTED.size());
		EXPECTED.forEach((key, expectedValue) -> {
			assertThat(actual).as("setting %s", key).containsKey(key);
			assertThat(actual.get(key)).as("value of %s", key).isEqualByComparingTo(expectedValue);
		});
	}

}
