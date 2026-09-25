package io.github.ciaran11221.compliance.repomap;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compares packages actually on disk under src/main/java against the packages CLAUDE.md's table
 * documents. Kept free of file I/O (plain sets in, plain sets out) so
 * PackageSetComparisonTest can prove it catches a missing or stale entry without touching real
 * source directories; same shape as RouteSetComparison for the route matrix.
 */
public record PackageSetComparison(Set<String> missingFromDoc, Set<String> extraInDoc) {

	public static PackageSetComparison compare(Set<String> onDisk, Set<String> documented) {
		Set<String> missingFromDoc = new LinkedHashSet<>(onDisk);
		missingFromDoc.removeAll(documented);
		Set<String> extraInDoc = new LinkedHashSet<>(documented);
		extraInDoc.removeAll(onDisk);
		return new PackageSetComparison(missingFromDoc, extraInDoc);
	}

	public boolean matches() {
		return missingFromDoc.isEmpty() && extraInDoc.isEmpty();
	}

	public String describe() {
		return "packages on disk but missing from CLAUDE.md: " + missingFromDoc
				+ "; packages CLAUDE.md documents that no longer exist: " + extraInDoc;
	}

}
