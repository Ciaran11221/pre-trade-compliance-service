package io.github.ciaran11221.compliance.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for the 75-5-10 diversification rule's bucket boundary (M9, issue #30 part a): a
 * buy that pushes the sum of "over" issuer positions past OVER_LIMIT_BUCKET_PCT of fund assets is
 * BLOCK from DiversificationRule, and a buy that keeps that sum at or under the bucket limit is
 * not BLOCK.
 *
 * A plain JUnit 5 test, not a Spring test: DiversificationRule is a pure function of an
 * OrderContext, so no database or context is needed. 1,000 cases are built by
 * generateCases() -- a fixed list of edge cases first, then 1,000 minus that many cases from a
 * java.util.SplittableRandom seeded by SEED (overridable with -Dm9.seed=N) -- and run in a single
 * loop inside one @Test, so the surefire report still shows one test per property. There is no
 * shrinking: a failing case is reported with the seed, its index and its full generated value, so
 * it can be re-created by hand.
 *
 * The expected outcome is computed here by an oracle written independently from
 * DiversificationRule / IssuerOverLimitCalculator, from the definition in DiversificationRule's own
 * javadoc, so the test cannot pass merely because it re-runs the same code the rule uses.
 *
 * Every security in a generated case is priced at exactly $1, so a position's dollar value equals
 * its share quantity. Fund assets and voting-shares-outstanding are always multiples of 100, and
 * ISSUER_LIMIT_PCT, VOTING_LIMIT_PCT and OVER_LIMIT_BUCKET_PCT are generated as whole-number
 * percentages within each key's hard bounds (read from LimitKey.bounds() at runtime, not hard
 * coded), which keeps every percentage threshold (issuer, voting, bucket) an exact integer number
 * of shares for any percentage the generator picks, so edge cases (a position at exactly the
 * limit, one share over it) are exact rather than rounded.
 *
 * "Over" for a position is (value > ISSUER_LIMIT_PCT% of assets) OR (quantity > VOTING_LIMIT_PCT%
 * of the security's voting shares outstanding); since both are plain ">" tests against the same
 * quantity, that OR is equivalent to quantity > min(issuerThreshold, voteThreshold), which is what
 * the oracle below uses as the "effective threshold" for the traded issuer. A pre-existing "other"
 * issuer, when included, is either strictly over its own issuer threshold (counts fully towards the
 * bucket sum, standing in for one or more other over-issuer-limit issuers already in the fund) or
 * at or under it (must not count towards the bucket at all) -- both are generated.
 *
 * Not covered: multiple "other" issuers at once, sells (DiversificationRule treats every sell as
 * NOT_APPLICABLE, so there is nothing for this property to check there), and a fund that is not
 * registered as diversified (same reason).
 */
class DiversificationBucketPropertyTest {

	private static final long SEED = Long.getLong("m9.seed", 300719L);

	private static final int CASE_COUNT = 1000;

	private static final BigDecimal ONE = BigDecimal.ONE;

	private static final long HUGE_VOTING_SHARES = 1_000_000_000_000L;

	private static final long BASE_TOTAL_ASSETS = 100_000_000L;

	private static final long BASE_VOTING_SHARES = 100_000_000L;

	private final DiversificationRule rule = new DiversificationRule();

	/** One generated scenario: a fund, an "other" issuer position (maybe), and a traded issuer. */
	record Case(long totalAssets, long votingShares, int issuerPct, int votingPct, int bucketPct,
			long tradePreValue, long buyQuantity, long otherValue, boolean hasOther) {
	}

	@Test
	void buyPastTheBucketLimitBlocksAndAtOrUnderDoesNot() {
		List<Case> cases = generateCases();
		assertThat(cases).as("seed %d: generator must produce exactly %d cases", SEED, CASE_COUNT)
			.hasSize(CASE_COUNT);

		int blockCount = 0;
		int notBlockCount = 0;

		for (int i = 0; i < cases.size(); i++) {
			Case c = cases.get(i);
			String label = "seed=" + SEED + " case=" + i + " " + c;

			long issuerThreshold = (c.totalAssets() / 100) * c.issuerPct();
			long voteThreshold = (c.votingShares() / 100) * c.votingPct();
			long effectiveThreshold = Math.min(issuerThreshold, voteThreshold);
			long bucketThreshold = (c.totalAssets() / 100) * c.bucketPct();

			long tradePostValue = c.tradePreValue() + c.buyQuantity();
			boolean tradeOverPre = c.tradePreValue() > effectiveThreshold;
			boolean tradeOverPost = tradePostValue > effectiveThreshold;
			boolean otherOver = c.hasOther() && c.otherValue() > issuerThreshold;
			long otherContribution = otherOver ? c.otherValue() : 0L;

			long overSumPre = otherContribution + (tradeOverPre ? c.tradePreValue() : 0L);
			long overSumPost = otherContribution + (tradeOverPost ? tradePostValue : 0L);

			boolean expectedBlock = overSumPost > bucketThreshold && overSumPost > overSumPre;

			OrderContext context = buildContext(c, tradePostValue);
			RuleResult result = rule.evaluate(context);

			if (expectedBlock) {
				blockCount++;
				assertThat(result.outcome()).as(
						"expected BLOCK: post-trade over-sum %d > bucket threshold %d and grew from %d (%s)",
						overSumPost, bucketThreshold, overSumPre, label).isEqualTo(RuleOutcome.BLOCK);
			}
			else {
				notBlockCount++;
				assertThat(result.outcome()).as(
						"expected not BLOCK: post-trade over-sum %d, bucket threshold %d, pre-trade over-sum %d (%s)",
						overSumPost, bucketThreshold, overSumPre, label).isNotEqualTo(RuleOutcome.BLOCK);
			}
		}

		System.out.println("M9 part a counters: blockCount=" + blockCount + " notBlockCount=" + notBlockCount + " of "
				+ CASE_COUNT + " cases (seed=" + SEED + ")");

		assertThat(blockCount)
			.as("seed %d: at least 100 of %d cases must be expected to BLOCK (saw %d)", SEED, CASE_COUNT, blockCount)
			.isGreaterThanOrEqualTo(100);
		assertThat(notBlockCount)
			.as("seed %d: at least 100 of %d cases must not be expected to BLOCK (saw %d)", SEED, CASE_COUNT,
					notBlockCount)
			.isGreaterThanOrEqualTo(100);
	}

	private OrderContext buildContext(Case c, long tradePostValueUnused) {
		Map<String, OrderContext.SecurityInfo> securities = new LinkedHashMap<>();
		securities.put("TRADE", new OrderContext.SecurityInfo("Trade Issuer", ONE, c.votingShares(), 1_000_000L));
		Map<String, Long> holdings = new LinkedHashMap<>();
		holdings.put("TRADE", c.tradePreValue());
		if (c.hasOther()) {
			securities.put("OTHER", new OrderContext.SecurityInfo("Other Issuer", ONE, HUGE_VOTING_SHARES, 1_000_000L));
			holdings.put("OTHER", c.otherValue());
		}

		OrderContext.Fund fund = new OrderContext.Fund(BigDecimal.valueOf(c.totalAssets()), BigDecimal.ZERO, true);
		OrderContext.Order order = new OrderContext.Order(OrderContext.Side.BUY, "TRADE", c.buyQuantity(), ONE);
		return new OrderContext(fund, order, holdings, securities, Set.of(), List.of(), limitsFor(c));
	}

	private Limits limitsFor(Case c) {
		Map<LimitKey, BigDecimal> values = new EnumMap<>(LimitKey.class);
		for (LimitKey key : LimitKey.values()) {
			values.put(key, key.defaultValue());
		}
		values.put(LimitKey.ISSUER_LIMIT_PCT, BigDecimal.valueOf(c.issuerPct()));
		values.put(LimitKey.VOTING_LIMIT_PCT, BigDecimal.valueOf(c.votingPct()));
		values.put(LimitKey.OVER_LIMIT_BUCKET_PCT, BigDecimal.valueOf(c.bucketPct()));
		return new Limits(values);
	}

	private List<Case> generateCases() {
		List<Case> cases = new ArrayList<>();
		addEdgeCases(cases);

		SplittableRandom random = new SplittableRandom(SEED);
		while (cases.size() < CASE_COUNT) {
			cases.add(randomCase(random));
		}
		return cases;
	}

	/**
	 * Explicit cases first: the default percentages (which for these three keys equal each key's
	 * legal maximum) and, for each key in turn, that key pinned at its lowest legal whole-number
	 * percentage while the other two stay at their maximum -- so every bound edge LimitKey declares
	 * is exercised at least once. For each of those five percentage combinations, a fixed set of
	 * threshold-crossing positions and "other issuer" placements (at-or-under its threshold, over
	 * it but not growing the bucket, and over it while growing the bucket) is added.
	 */
	private void addEdgeCases(List<Case> cases) {
		int issuerMax = LimitKey.ISSUER_LIMIT_PCT.defaultValue().intValueExact();
		int votingMax = LimitKey.VOTING_LIMIT_PCT.defaultValue().intValueExact();
		int bucketMax = LimitKey.OVER_LIMIT_BUCKET_PCT.defaultValue().intValueExact();
		int issuerMin = minWholePct(LimitKey.ISSUER_LIMIT_PCT);
		int votingMin = minWholePct(LimitKey.VOTING_LIMIT_PCT);
		int bucketMin = minWholePct(LimitKey.OVER_LIMIT_BUCKET_PCT);

		int[][] pctCombos = { { issuerMax, votingMax, bucketMax }, { issuerMin, votingMax, bucketMax },
				{ issuerMax, votingMin, bucketMax }, { issuerMax, votingMax, bucketMin },
				{ issuerMin, votingMin, bucketMin } };

		for (int[] combo : pctCombos) {
			addEdgeCasesForPcts(cases, combo[0], combo[1], combo[2]);
		}
	}

	/** The smallest legal whole-number percentage for a key whose lower bound is MIN_EXCLUSIVE 0. */
	private int minWholePct(LimitKey key) {
		return key.bounds()
			.stream()
			.filter(b -> b.kind() == LimitKey.Bound.Kind.MIN_EXCLUSIVE)
			.map(b -> b.value().intValueExact() + 1)
			.findFirst()
			.orElseThrow();
	}

	private void addEdgeCasesForPcts(List<Case> cases, int issuerPct, int votingPct, int bucketPct) {
		long issuerThreshold = (BASE_TOTAL_ASSETS / 100) * issuerPct;
		long voteThreshold = (BASE_VOTING_SHARES / 100) * votingPct;
		long effectiveThreshold = Math.min(issuerThreshold, voteThreshold);
		long bucketThreshold = (BASE_TOTAL_ASSETS / 100) * bucketPct;

		// A minimal buy from nothing: almost always PASS.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0, 1, 0, false));
		// Sitting exactly at the effective threshold, then one more share crosses it.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, effectiveThreshold,
				1, 0, false));
		// Already over pre-trade; buying more grows an already-over position.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct,
				effectiveThreshold + 1, 1, 0, false));
		// Buying exactly up to the threshold (post value == threshold, not over: "over" is strict).
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0,
				effectiveThreshold, 0, false));
		// Buying one past the threshold from nothing.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0,
				effectiveThreshold + 1, 0, false));
		// An "other" issuer sitting exactly at its own threshold: at-or-under, so it must not count.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0,
				effectiveThreshold + 1, issuerThreshold, true));
		// An "other" issuer over its threshold, but this trade itself stays small: the bucket sum
		// does not grow from this order, so it must not BLOCK even if the bucket is already breached.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0, 1,
				issuerThreshold + 1, true));
		// An "other" issuer over its threshold AND this trade grows the bucket sum past the limit.
		cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0,
				effectiveThreshold + 1, issuerThreshold + 1, true));
		if (bucketThreshold > issuerThreshold) {
			// The bucket sum lands exactly at its limit through the "other" issuer alone: not over.
			cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0, 1,
					bucketThreshold, true));
			// One dollar past the bucket limit through the "other" issuer alone, with no growth from
			// this trade: DiversificationRule's javadoc says a fund already over 25% is not forced to
			// sell and may still make a trade that does not itself grow the over-limit group.
			cases.add(new Case(BASE_TOTAL_ASSETS, BASE_VOTING_SHARES, issuerPct, votingPct, bucketPct, 0, 1,
					bucketThreshold + 1, true));
		}
	}

	private Case randomCase(SplittableRandom random) {
		long totalAssets = random.nextLong(10, 20_000_000 + 1) * 100;
		long votingShares = random.nextLong(1, 20_000_000 + 1) * 100;

		int issuerPct = randomPct(random, LimitKey.ISSUER_LIMIT_PCT);
		int votingPct = randomPct(random, LimitKey.VOTING_LIMIT_PCT);
		int bucketPct = randomPct(random, LimitKey.OVER_LIMIT_BUCKET_PCT);

		long issuerThreshold = (totalAssets / 100) * issuerPct;
		long voteThreshold = (votingShares / 100) * votingPct;
		long effectiveThreshold = Math.min(issuerThreshold, voteThreshold);
		long span = effectiveThreshold * 3 + 1000;

		long preValue = randomPreValue(random, effectiveThreshold, span);
		long toThreshold = effectiveThreshold - preValue;
		long buy = randomBuy(random, toThreshold, span);

		boolean hasOther = random.nextBoolean();
		long otherValue = 0L;
		if (hasOther) {
			boolean over = random.nextBoolean();
			otherValue = over ? issuerThreshold + 1 + random.nextLong(0, issuerThreshold + span + 1)
					: random.nextLong(0, issuerThreshold + 1);
		}

		return new Case(totalAssets, votingShares, issuerPct, votingPct, bucketPct, preValue, buy, otherValue,
				hasOther);
	}

	/** A whole-number percentage within key's hard bounds, weighted towards its two bound edges. */
	private int randomPct(SplittableRandom random, LimitKey key) {
		int max = key.defaultValue().intValueExact();
		int min = minWholePct(key);
		if (random.nextInt(5) == 0) {
			return random.nextBoolean() ? min : max;
		}
		return min == max ? min : random.nextInt(min, max + 1);
	}

	private long randomPreValue(SplittableRandom random, long effectiveThreshold, long span) {
		return switch (random.nextInt(4)) {
			case 0 -> 0L;
			case 1 -> effectiveThreshold;
			case 2 -> effectiveThreshold + 1;
			default -> random.nextLong(0, span + 1);
		};
	}

	private long randomBuy(SplittableRandom random, long toThreshold, long span) {
		if (random.nextInt(3) != 0) {
			return random.nextLong(1, span + 2);
		}
		List<Long> edgeBuys = new ArrayList<>();
		edgeBuys.add(1L);
		if (toThreshold > 0) {
			edgeBuys.add(toThreshold);
			edgeBuys.add(toThreshold + 1);
		}
		return edgeBuys.get(random.nextInt(edgeBuys.size()));
	}

}
