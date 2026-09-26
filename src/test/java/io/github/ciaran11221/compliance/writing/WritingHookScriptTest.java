package io.github.ciaran11221.compliance.writing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real .githooks/check-writing.sh, the script CI uses on commit messages, over the same
 * cases WritingCheckTest runs through the Java check. The two are separate implementations, so
 * without this a shell-side bug (an earlier version missed every multi-word entry) passes the build.
 * Skipped only where sh or perl is not on the PATH; CI's Linux runner has both.
 */
class WritingHookScriptTest {

	@ParameterizedTest(name = "[{0}] -> exit {1}")
	@CsvSource(delimiter = '|', value = { "a seamless flow | 1", "Robustness matters | 1", "In  summary, done | 1",
			"it’s worth noting | 1", "IT'S WORTH NOTING | 1", "in conclusion | 1", "rocket 🚀 | 1",
			"one — two | 1", "an en dash – is fine | 0", "a robot | 0", "a plain sentence | 0" })
	void theHookScriptAgreesWithTheJavaCheck(String text, int expectedExit) throws Exception {
		assumeTrue(onPath("sh") && onPath("perl"), "sh and perl are needed to run the hook script");

		Process process = new ProcessBuilder("sh", ".githooks/check-writing.sh").redirectErrorStream(true).start();
		try (OutputStream in = process.getOutputStream()) {
			in.write((text + "\n").getBytes(StandardCharsets.UTF_8));
		}
		assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("script finished").isTrue();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(process.exitValue()).as("exit code for [%s], output: %s", text, output).isEqualTo(expectedExit);
	}

	private static boolean onPath(String command) {
		try {
			Process probe = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
			probe.getInputStream().readAllBytes();
			return probe.waitFor(10, TimeUnit.SECONDS);
		}
		catch (IOException | InterruptedException ex) {
			return false;
		}
	}

}
