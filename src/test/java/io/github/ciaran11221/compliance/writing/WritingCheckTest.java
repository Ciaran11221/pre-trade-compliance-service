package io.github.ciaran11221.compliance.writing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans README.md and files under docs/ (recursively, text files) for banned words, emoji and em
 * dash. Fails with file:line: word for each hit. Tests prove the check bites: various forms of
 * banned words are caught, and non-banned words pass.
 */
class WritingCheckTest {

	private static final Path README = Path.of("README.md");

	private static final Path DOCS = Path.of("docs");

	private static final List<String> BANNED_WORDS = loadBannedWords();

	@Test
	void readmeAndDocsPassWritingCheck() throws IOException {
		List<String> violations = new ArrayList<>();

		if (Files.exists(README)) {
			String content = Files.readString(README, StandardCharsets.UTF_8);
			checkContent(content, README.toString(), violations);
		}

		if (Files.exists(DOCS)) {
			try (Stream<Path> paths = Files.walk(DOCS)) {
				paths.filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith(".md"))
					.filter(p -> !p.equals(DOCS.resolve("banned-words.txt")))  // Skip the banned words list itself
					.forEach(p -> {
						try {
							String content = Files.readString(p, StandardCharsets.UTF_8);
							checkContent(content, p.toString(), violations);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});
			}
		}

		assertThat(violations).as("banned words, emoji or em dash found")
			.isEmpty();
	}

	@Test
	void seamlessFails() {
		List<String> violations = checkString("a seamless flow");
		assertThat(violations).isNotEmpty();
		assertThat(violations.get(0)).contains("seamless");
	}

	@Test
	void robustnessCapitalizedFails() {
		List<String> violations = checkString("Robustness");
		assertThat(violations).isNotEmpty();
	}

	@Test
	void multiSpaceInSummaryFails() {
		List<String> violations = checkString("In  summary");
		assertThat(violations).isNotEmpty();
	}

	@Test
	void curlyApostropheItWorthNotingFails() {
		// Using unicode escape for right single quotation mark U+2019
		List<String> violations = checkString("it’s worth noting");
		assertThat(violations).isNotEmpty();
	}

	@Test
	void emojiCharacterFails() {
		List<String> violations = checkString("this is cool 😀");
		assertThat(violations).isNotEmpty();
		assertThat(violations.get(0)).contains("emoji");
	}

	@Test
	void emDashFails() {
		// Using unicode escape for em dash U+2014
		List<String> violations = checkString("dash here — like this");
		assertThat(violations).isNotEmpty();
		assertThat(violations.get(0)).contains("em dash");
	}

	@Test
	void enDashPasses() {
		// Using unicode escape for en dash U+2013
		List<String> violations = checkString("an en dash – is fine");
		assertThat(violations).isEmpty();
	}

	@Test
	void robotDoesNotHit() {
		List<String> violations = checkString("robot");
		assertThat(violations).isEmpty();
	}

	private List<String> checkString(String content) {
		List<String> violations = new ArrayList<>();
		checkContent(content, "test", violations);
		return violations;
	}

	private void checkContent(String content, String filePath, List<String> violations) {
		String[] lines = content.split("\n", -1);
		for (int lineNum = 1; lineNum <= lines.length; lineNum++) {
			String line = lines[lineNum - 1];

			// Check for banned words
			for (String word : BANNED_WORDS) {
				String matched = findBannedWord(line, word);
				if (matched != null) {
					violations.add(filePath + ":" + lineNum + ": " + matched);
					break;
				}
			}

			// Check for emoji (U+1F300-U+1FAFF, U+2600-U+27BF, U+1F000-U+1F2FF)
			if (containsEmoji(line)) {
				violations.add(filePath + ":" + lineNum + ": [emoji]");
				continue;
			}

			// Check for em dash (U+2014)
			if (line.contains("—")) {
				violations.add(filePath + ":" + lineNum + ": [em dash]");
			}
		}
	}

	private boolean containsEmoji(String text) {
		for (int i = 0; i < text.length(); i++) {
			int codePoint = text.codePointAt(i);
			// U+1F300-U+1FAFF, U+2600-U+27BF, U+1F000-U+1F2FF
			if ((codePoint >= 0x1F300 && codePoint <= 0x1FAFF) ||
				(codePoint >= 0x2600 && codePoint <= 0x27BF) ||
				(codePoint >= 0x1F000 && codePoint <= 0x1F2FF)) {
				return true;
			}
			// Skip low surrogates
			if (Character.isLowSurrogate(text.charAt(i))) {
				i++;
			}
		}
		return false;
	}

	private String findBannedWord(String line, String word) {
		// First, normalize curly quotes to straight quotes (U+2019 to ')
		String normalized = word.replace("’", "'");

		// Build pattern: escape regex special chars, handle spaces and apostrophes
		StringBuilder pattern = new StringBuilder();
		int i = 0;
		while (i < normalized.length()) {
			char c = normalized.charAt(i);
			if (c == ' ') {
				// Space matches flexible whitespace
				pattern.append("\\s+");
				// Skip consecutive spaces
				while (i + 1 < normalized.length() && normalized.charAt(i + 1) == ' ') {
					i++;
				}
			} else if (c == '\'') {
				// Apostrophe matches straight or curly quote (U+2019)
				pattern.append("['’]");
			} else if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
				// Escape regex special characters
				pattern.append("\\").append(c);
			} else {
				pattern.append(c);
			}
			i++;
		}

		// Match at word start, may be followed by letters, case-insensitive
		Pattern p = Pattern.compile("\\b" + pattern.toString(), Pattern.CASE_INSENSITIVE);
		Matcher matcher = p.matcher(line);
		if (matcher.find()) {
			return matcher.group();  // Return the actual matched text
		}
		return null;
	}

	private static List<String> loadBannedWords() {
		Path bannedWordsFile = Path.of("docs/banned-words.txt");
		List<String> words = new ArrayList<>();

		if (Files.exists(bannedWordsFile)) {
			try {
				String content = Files.readString(bannedWordsFile, StandardCharsets.UTF_8);
				String[] lines = content.split("\n");
				for (String line : lines) {
					line = line.trim();
					if (!line.isEmpty() && !line.startsWith("#")) {
						words.add(line);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return words;
	}

}
