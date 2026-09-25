package io.github.ciaran11221.compliance.repomap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the two markdown tables under CLAUDE.md's "Where things live" heading: the package table
 * (backticked package names) and the path table (backticked paths). Plain line-based parsing --
 * the table shape is simple and fixed, and a real markdown parser would be more machinery than
 * this needs.
 */
public final class RepoMapDoc {

	private static final String HEADING = "## Where things live";

	private RepoMapDoc() {
	}

	public record Tables(Set<String> packages, List<String> paths) {
	}

	public static Tables read(Path claudeMd) {
		List<String> lines;
		try {
			lines = Files.readAllLines(claudeMd, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + claudeMd, ex);
		}

		List<List<String>> tables = tablesUnder(lines);
		if (tables.size() != 2) {
			throw new IllegalStateException(
					"Expected 2 tables under \"" + HEADING + "\" in " + claudeMd + ", found " + tables.size());
		}

		Set<String> packages = firstCells(tables.get(0));
		List<String> paths = firstCells(tables.get(1)).stream()
			.filter(path -> path.startsWith("src/") || path.startsWith("docs/"))
			.toList();

		return new Tables(packages, paths);
	}

	private static List<List<String>> tablesUnder(List<String> lines) {
		int start = lines.indexOf(HEADING);
		if (start < 0) {
			throw new IllegalStateException("Heading not found: " + HEADING);
		}
		int end = lines.size();
		for (int i = start + 1; i < lines.size(); i++) {
			if (lines.get(i).startsWith("## ")) {
				end = i;
				break;
			}
		}

		List<List<String>> tables = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (int i = start + 1; i < end; i++) {
			String line = lines.get(i);
			if (line.startsWith("|")) {
				current.add(line);
			}
			else if (!current.isEmpty()) {
				tables.add(current);
				current = new ArrayList<>();
			}
		}
		if (!current.isEmpty()) {
			tables.add(current);
		}
		return tables;
	}

	private static Set<String> firstCells(List<String> tableLines) {
		Set<String> cells = new LinkedHashSet<>();
		// tableLines[0] is the header row, tableLines[1] is the "|---|---|" separator row.
		for (int i = 2; i < tableLines.size(); i++) {
			cells.add(firstCell(tableLines.get(i)));
		}
		return cells;
	}

	private static String firstCell(String row) {
		// row.split("|")[0] is empty (before the leading pipe); [1] is the first real cell.
		String[] parts = row.split("\\|", -1);
		return parts[1].trim().replace("`", "");
	}

}
