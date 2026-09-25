package io.github.ciaran11221.compliance.scenario;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real YAML parsing against the real corpus, so a mistake in the Scenario record
 * shapes (a wrong field name, a type Jackson can't coerce) shows up here rather than only inside
 * ScenarioIndexTest or ScenarioCoverageTest, which would report it as something else entirely.
 */
class ScenarioLoaderTest {

	@Test
	void loadsEveryScenarioSortedById() {
		List<Scenario> scenarios = ScenarioLoader.loadAllValidated();

		assertThat(scenarios).extracting(Scenario::id)
			.containsExactly("S001", "S002", "S003", "S004", "S005", "S006", "S007", "S008", "S009", "S010", "S011",
					"S012", "S013", "S014", "S015", "S016", "S017", "S018", "S019", "S020");
	}

	@Test
	void issuerParsesWhenPresentAndDefaultsToNullWhenAbsent() {
		List<Scenario> scenarios = ScenarioLoader.loadAllValidated();
		Scenario s001 = scenarios.stream().filter(s -> s.id().equals("S001")).findFirst().orElseThrow();
		Scenario s007 = scenarios.stream().filter(s -> s.id().equals("S007")).findFirst().orElseThrow();

		assertThat(s001.given().securities()).extracting(Scenario.Security::issuer).containsOnlyNulls();
		assertThat(s007.given().securities())
			.filteredOn(security -> security.ticker().equals("EMBA") || security.ticker().equals("EMBB"))
			.extracting(Scenario.Security::issuer)
			.containsOnly("Emberlyn Group");
	}

	@Test
	void moneyFieldsParseAsBigDecimal() {
		Scenario s001 = ScenarioLoader.loadAllValidated().stream().filter(s -> s.id().equals("S001")).findFirst().orElseThrow();

		assertThat(s001.given().fund().code()).isEqualTo("HGF");
		assertThat(s001.given().fund().totalAssets()).isEqualByComparingTo(new BigDecimal("1000000000"));
		assertThat(s001.given().securities()).extracting(Scenario.Security::ticker).contains("KSTL", "NRTH", "VLCN");
		assertThat(s001.when().order()).isEqualTo(new Scenario.Order("BUY", "KSTL", 100000));
		assertThat(s001.then().outcome()).isEqualTo("PASS");
		assertThat(s001.then().rules()).containsEntry("diversification", "PASS");
	}

	@Test
	void pendingOrdersParseWhenPresent() {
		Scenario s005 = ScenarioLoader.loadAllValidated().stream().filter(s -> s.id().equals("S005")).findFirst().orElseThrow();

		assertThat(s005.given().pendingOrders()).containsExactly(new Scenario.Order("BUY", "NRTH", 40000));
	}

}
