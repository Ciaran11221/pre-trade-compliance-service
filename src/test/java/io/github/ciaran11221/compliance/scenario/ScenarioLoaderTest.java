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
					"S012", "S013", "S014", "S015", "S016", "S017", "S018", "S019", "S020", "S021", "S022", "S023",
					"S024", "S025", "S026", "S027", "S028", "S029", "S030", "S031", "S032", "S033", "S034");
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

	/**
	 * S001-S005 name a fixture: (M10 part 2, issue #31) instead of an inline given:.
	 * ScenarioLoader.loadAllValidated() must resolve that fixture transparently, so a consumer like
	 * RuleScenarioTest sees exactly the same given() a still-inline scenario (S006 onward) would
	 * produce, reading fixtures/fund-state/hgf-150m-over-5.yaml verbatim.
	 */
	@Test
	void aFixtureReferenceResolvesToTheSameGivenTheFixtureFileHolds() {
		Scenario s001 = ScenarioLoader.loadAllValidated().stream().filter(s -> s.id().equals("S001")).findFirst().orElseThrow();

		assertThat(s001.fixture()).isEqualTo("hgf-150m-over-5");
		Scenario.Given fromFixtureFile = FixtureLoader.load("hgf-150m-over-5");
		assertThat(s001.given()).isEqualTo(fromFixtureFile);
	}

}
