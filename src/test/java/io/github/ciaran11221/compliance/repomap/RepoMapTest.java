package io.github.ciaran11221.compliance.repomap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks src/main/java/io/github/ciaran11221/compliance and checks every package directory that
 * holds a .java file is named in CLAUDE.md's package table, and every path CLAUDE.md's path
 * table names actually exists on disk. Reads relative paths, same as ScenarioIndexTest and
 * RouteAccessCsv's classpath reads -- Maven Surefire's working directory is the module root.
 */
class RepoMapTest {

	private static final Path CLAUDE_MD = Path.of("CLAUDE.md");

	private static final Path COMPLIANCE_ROOT = Path.of("src/main/java/io/github/ciaran11221/compliance");

	@Test
	void everyPackageOnDiskIsDocumented() {
		Set<String> onDisk = RepoPackages.onDisk(COMPLIANCE_ROOT);
		Set<String> documented = RepoMapDoc.read(CLAUDE_MD).packages();

		PackageSetComparison comparison = PackageSetComparison.compare(onDisk, documented);

		assertThat(comparison.matches()).as(comparison.describe()).isTrue();
	}

	@Test
	void everyDocumentedPathExistsOnDisk() {
		List<String> paths = RepoMapDoc.read(CLAUDE_MD).paths();

		List<String> missing = missing(paths);

		assertThat(missing).as("paths CLAUDE.md documents but that do not exist on disk").isEmpty();
	}

	static List<String> missing(List<String> paths) {
		List<String> missing = new ArrayList<>();
		for (String path : paths) {
			Path p = Path.of(path);
			boolean exists = path.endsWith("/") ? Files.isDirectory(p) : Files.isRegularFile(p);
			if (!exists) {
				missing.add(path);
			}
		}
		return missing;
	}

}
