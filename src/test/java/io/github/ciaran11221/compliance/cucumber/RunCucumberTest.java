package io.github.ciaran11221.compliance.cucumber;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Runs every scenario in src/test/resources/features/*.feature as a real JUnit Platform test
 * inside ./mvnw verify (M10 part 2, issue #31). Named "...Test" -- not "...Suite" -- on purpose:
 * Surefire's default include patterns only pick up "**&#47;*Test.java" (among a few others), and a
 * @Suite class under any other name would be silently skipped, compiling fine and never running a
 * single scenario while the build still passes -- exactly the "0 scenarios and still green"
 * failure mode issue #31 warns about (see ScenarioIndexTest's committed proof-of-failure output in
 * the pull request description for the same concern applied to the JUnit corpus).
 *
 * <p>
 * Glue is this package, explicit rather than left to default (whole-classpath) scanning, so a stray
 * step-annotated class anywhere else on the test classpath can never silently join this suite's
 * glue by accident.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.github.ciaran11221.compliance.cucumber")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
public class RunCucumberTest {

}
