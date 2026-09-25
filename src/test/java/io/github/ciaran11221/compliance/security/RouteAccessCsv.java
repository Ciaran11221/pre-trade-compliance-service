package io.github.ciaran11221.compliance.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads src/test/resources/route-access.csv: header "method,path,roles", roles ';'-separated. */
public final class RouteAccessCsv {

	public static final String PUBLIC = "PUBLIC";

	public static final String ANY = "ANY";

	public record Row(String method, String path, Set<String> roles) {

		public boolean isPublic() {
			return roles.contains(PUBLIC);
		}

		public boolean allowsRole(String role) {
			return roles.contains(ANY) || roles.contains(role);
		}

	}

	private RouteAccessCsv() {
	}

	public static List<Row> read(String classpathResource) {
		List<Row> rows = new ArrayList<>();
		try (InputStream in = RouteAccessCsv.class.getClassLoader().getResourceAsStream(classpathResource)) {
			if (in == null) {
				throw new IllegalStateException("Not found on the classpath: " + classpathResource);
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line = reader.readLine(); // header, skipped
				while ((line = reader.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					String[] fields = line.split(",", 3);
					Set<String> roles = Arrays.stream(fields[2].split(";"))
						.map(String::trim)
						.collect(Collectors.toUnmodifiableSet());
					rows.add(new Row(fields[0].trim(), fields[1].trim(), roles));
				}
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + classpathResource, ex);
		}
		return rows;
	}

}
