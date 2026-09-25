package io.github.ciaran11221.compliance.repomap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Computes the package names RepoMapTest expects CLAUDE.md's package table to name, straight
 * from the directories under src/main/java/io/github/ciaran11221/compliance. A directory counts
 * only if it holds at least one .java file directly: a future grouping folder with no .java of
 * its own (for example "rules", holding only a "diversification" subfolder) never needs its own
 * row, only "rules.diversification" does.
 */
public final class RepoPackages {

	private RepoPackages() {
	}

	public static Set<String> onDisk(Path complianceRoot) {
		Set<String> packages = new LinkedHashSet<>();
		try (Stream<Path> dirs = Files.walk(complianceRoot)) {
			dirs.filter(Files::isDirectory)
				.forEach(dir -> {
					if (hasJavaFileDirectly(dir)) {
						packages.add(packageName(complianceRoot, dir));
					}
				});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return packages;
	}

	private static boolean hasJavaFileDirectly(Path dir) {
		try (Stream<Path> children = Files.list(dir)) {
			return children.anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String packageName(Path root, Path dir) {
		if (dir.equals(root)) {
			return "compliance";
		}
		StringBuilder name = new StringBuilder();
		for (Path part : root.relativize(dir)) {
			if (!name.isEmpty()) {
				name.append('.');
			}
			name.append(part);
		}
		return name.toString();
	}

}
