package io.github.ciaran11221.compliance.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Test scope only -- see requirement 8 and the jar check in .github/workflows/ci.yml, which fails
 * the build if this class ends up in the runtime jar. Signs a token with the local-dev secret
 * (must match application-local.yml's compliance.security.jwt-secret) so a developer running the
 * service locally can call it without a real identity provider.
 *
 * Usage:
 *   ./mvnw -q test-compile exec:java -Dexec.mainClass=io.github.ciaran11221.compliance.support.TokenTool \
 *       -Dexec.classpathScope=test -Dexec.args="anne TRADER"
 */
public final class TokenTool {

	// Must match application-local.yml's compliance.security.jwt-secret exactly.
	private static final String LOCAL_DEV_SECRET = "local-dev-only-secret-do-not-use-in-any-other-environment";

	private static final int DEFAULT_MINUTES_VALID = 60;

	private TokenTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: TokenTool <staffId> <ROLE>[,<ROLE>...] [minutesValid]");
			System.exit(1);
			return;
		}
		String staffId = args[0];
		List<String> roles = Arrays.asList(args[1].split(","));
		int minutesValid = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_MINUTES_VALID;

		System.out.println(signedToken(staffId, roles, minutesValid, LOCAL_DEV_SECRET));
	}

	/**
	 * Reusable by other tests that need a token signed with an arbitrary secret (usually the
	 * test-profile secret in application-test.yml, not the local-dev one above).
	 */
	public static String signedToken(String staffId, List<String> roles, int minutesValid, String secret)
			throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.subject(staffId)
			.claim("roles", roles)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(minutesValid * 60L)))
			.build();
		SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
		return signedJwt.serialize();
	}

}
