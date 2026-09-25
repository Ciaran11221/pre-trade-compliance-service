package io.github.ciaran11221.compliance.repomap;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test: proves RepoMapTest's package check actually bites. No real source directories or
 * CLAUDE.md edits here on purpose -- PackageSetComparison is a plain function of two sets.
 */
class PackageSetComparisonTest {

	@Test
	void matchesWhenBothSetsAreIdentical() {
		Set<String> packages = Set.of("compliance", "me", "reference", "security");

		assertThat(PackageSetComparison.compare(packages, packages).matches()).isTrue();
	}

	@Test
	void aPackageOnDiskButMissingFromTheDocFailsTheMatch() {
		Set<String> onDisk = Set.of("compliance", "me", "rules.diversification");
		Set<String> documented = Set.of("compliance", "me");

		PackageSetComparison result = PackageSetComparison.compare(onDisk, documented);

		assertThat(result.matches()).isFalse();
		assertThat(result.missingFromDoc()).containsExactly("rules.diversification");
		assertThat(result.describe()).contains("rules.diversification");
	}

	@Test
	void aDocumentedPackageThatNoLongerExistsFailsTheMatch() {
		Set<String> onDisk = Set.of("compliance");
		Set<String> documented = Set.of("compliance", "ghost");

		PackageSetComparison result = PackageSetComparison.compare(onDisk, documented);

		assertThat(result.matches()).isFalse();
		assertThat(result.extraInDoc()).containsExactly("ghost");
	}

}
