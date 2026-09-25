package io.github.ciaran11221.compliance.repomap;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Meta-test: proves RepoMapTest.missing() actually flags a path that isn't there. */
class RepoMapPathCheckTest {

	@Test
	void aRealFileAndDirectoryAreNotFlagged() {
		assertThat(RepoMapTest.missing(List.of("CLAUDE.md", "src/main/java/"))).isEmpty();
	}

	@Test
	void aPathThatDoesNotExistIsFlagged() {
		assertThat(RepoMapTest.missing(List.of("docs/does-not-exist.md"))).containsExactly("docs/does-not-exist.md");
	}

}
