package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the currently active value of every firm limit: for each key, the setting_value row with
 * the latest active_from that is still at or before now. A row with a future active_from (an
 * approved change not yet in effect) stays invisible until the injected Clock passes it.
 */
@Component
public class LimitsRepository {

	// A change never takes effect if it was cancelled before its activates_at (see
	// limits.LimitChangeService.cancel): setting_value is insert-only, so the row this join
	// excludes was already written, and this query is what makes it invisible rather than deleting
	// it. change_request_id is NULL for the day-one defaults V3 loads, which never matches a
	// cancellation's request_id, so those rows are never excluded by this join.
	private static final String ACTIVE_VALUE_SQL = """
			SELECT sv.value FROM setting_value sv
			WHERE sv.setting_key = ? AND sv.active_from <= ?
			  AND NOT EXISTS (
			      SELECT 1 FROM limit_change_cancellation c WHERE c.request_id = sv.change_request_id
			  )
			ORDER BY sv.active_from DESC, sv.id DESC
			LIMIT 1
			""";

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	public LimitsRepository(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	public Limits activeLimits() {
		Instant now = clock.instant();
		Map<LimitKey, BigDecimal> values = new EnumMap<>(LimitKey.class);
		for (LimitKey key : LimitKey.values()) {
			BigDecimal value = jdbcTemplate.queryForObject(ACTIVE_VALUE_SQL, BigDecimal.class, key.name(),
					Timestamp.from(now));
			values.put(key, value);
		}
		return new Limits(values);
	}

}
