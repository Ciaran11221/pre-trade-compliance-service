package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.reference.Staff;
import io.github.ciaran11221.compliance.reference.StaffRepository;
import io.github.ciaran11221.compliance.rules.ComplianceEngine;
import io.github.ciaran11221.compliance.rules.LimitKey;
import io.github.ciaran11221.compliance.rules.Limits;
import io.github.ciaran11221.compliance.rules.LimitsRepository;
import io.github.ciaran11221.compliance.rules.OrderContext;
import io.github.ciaran11221.compliance.rules.RuleResult;

/**
 * Order intake (spec 3.1): idempotent create, per-fund locking, running the compliance engine and
 * storing an explainable decision, plus read, fill and cancel. Each public method is one
 * transaction, mirroring limits.LimitChangeService.
 */
@Service
public class OrderService {

	private static final int PAGE_SIZE = 20;

	private static final List<String> TERMINAL_EVENT_TYPES = List.of("FILLED", "CANCELLED");

	private final OrderRepository orderRepository;

	private final LimitsRepository limitsRepository;

	private final ComplianceEngine complianceEngine;

	private final StaffRepository staffRepository;

	private final Clock clock;

	private final ObjectMapper objectMapper;

	public OrderService(OrderRepository orderRepository, LimitsRepository limitsRepository,
			ComplianceEngine complianceEngine, StaffRepository staffRepository, Clock clock,
			ObjectMapper objectMapper) {
		this.orderRepository = orderRepository;
		this.limitsRepository = limitsRepository;
		this.complianceEngine = complianceEngine;
		this.staffRepository = staffRepository;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	/**
	 * Step order deliberately does NOT match spec 3.1's diagram literally (idempotency check, then
	 * lock the fund row): the fund row is locked FIRST here, and the idempotency lookup happens
	 * while holding it.
	 *
	 * <p>
	 * Two threads racing the same NEW clientOrderId for the SAME fund, under the spec's literal
	 * order, would both find no existing order, both build a decision and both attempt to insert.
	 * trade_order.client_order_id's UNIQUE constraint stops a second ROW being written, but the
	 * loser would then see a raw DuplicateKeyException instead of an ordinary response identical to
	 * the winner's -- which the milestone rules out ("never a 500"; "both callers get the same
	 * response"). Locking the fund row first serialises the two threads instead: the loser blocks on
	 * Postgres' row lock until the winner's transaction commits (releasing the lock and making the
	 * winner's insert visible together), then runs the idempotency lookup itself and finds the row
	 * the winner just wrote, replaying that response rather than ever reaching the insert.
	 *
	 * <p>
	 * The insert below still has a conflict fallback (ON CONFLICT DO NOTHING, then replay), for the one case the
	 * fund lock alone cannot cover: two requests that share a clientOrderId but target DIFFERENT
	 * fundIds. Those take two different row locks and can still race each other on the unique
	 * constraint, so that path also needs to resolve to a clean replay-or-409 rather than a 500.
	 *
	 * <p>
	 * This same "hold the fund lock across the whole read-then-write sequence" property is also
	 * what makes the per-fund cash race safe: two different buys for one fund, each affordable
	 * alone, serialise on this lock, so the second order's pending-exposure read
	 * (OrderRepository.findPendingOrders) is guaranteed to see the first order's already-committed
	 * decision before deciding its own.
	 */
	@Transactional
	public OrderView submit(String submitterId, OrderRequestBody body) {
		List<OrdersProblems.FieldError> errors = validate(body);
		if (!errors.isEmpty()) {
			throw OrdersProblems.badRequest(errors);
		}

		String clientOrderId = body.clientOrderId().trim();
		long fundId = body.fundId();
		OrderContext.Side side = OrderContext.Side.valueOf(body.side().trim().toUpperCase(Locale.ROOT));
		String ticker = body.ticker().trim().toUpperCase(Locale.ROOT);
		long quantity = body.quantity();

		String hash = canonicalHash(clientOrderId, fundId, side, ticker, quantity);

		OrderRepository.FundRow fund = orderRepository.lockFund(fundId)
			.orElseThrow(() -> OrdersProblems.notFound("no fund with id " + fundId));

		Optional<OrderRepository.OrderRow> existing = orderRepository.findOrderByClientOrderId(clientOrderId);
		if (existing.isPresent()) {
			return replay(existing.get(), hash);
		}

		OrderRepository.SecurityRow security = orderRepository.findSecurityByTicker(ticker)
			.orElseThrow(() -> OrdersProblems.notFound("no security with ticker " + ticker));

		Instant now = clock.instant();
		Limits limits = limitsRepository.activeLimits();

		// SEAM for M7b (quarantine, issue #14), now filled in: sender-out-of-office and
		// lookback/duplicate checks run here, between the fund lock/idempotency check above and the
		// compliance engine below -- both need the fund lock already held (they read this fund's
		// recent/pending orders) and must run before the engine, per spec 3.1's order-flow diagram.
		Optional<QuarantineDecision> quarantineDecision = checkQuarantine(fundId, security.id(), submitterId, side,
				quantity, now, limits);

		Optional<Long> inserted = orderRepository.insertOrder(clientOrderId, fundId, security.id(), side, quantity,
				security.price(), submitterId, now, hash);
		if (inserted.isEmpty()) {
			// See the Javadoc above: only reachable when two requests share a clientOrderId but
			// target different funds. Fall back to the same replay-or-409 path.
			OrderRepository.OrderRow raced = orderRepository.findOrderByClientOrderId(clientOrderId)
				.orElseThrow(() -> new IllegalStateException("clientOrderId " + clientOrderId
						+ " conflicted on insert but no stored order was found"));
			return replay(raced, hash);
		}
		long orderId = inserted.get();

		if (quarantineDecision.isPresent()) {
			recordQuarantine(orderId, submitterId, now, limits, quarantineDecision.get());
			return buildOrderView(orderId);
		}

		OrderContext context = buildContext(fund, side, ticker, quantity, security.price(), limits, now, -1L);
		runEngineAndStoreDecision(orderId, context, submitterId, now);

		return buildOrderView(orderId);
	}

	/**
	 * Reuse point for QuarantineService.release() (issue #14's "reuse, don't duplicate, the
	 * decision-storing code"): runs the same compliance engine over the same kind of context submit()
	 * builds, then stores the decision the same way. The caller (QuarantineService) is responsible
	 * for everything release-specific: locking the quarantine row, the sender/out-of-office checks,
	 * and taking the fund lock (OrderRepository.lockFund) before calling this -- trap #7's "release
	 * must take the SAME fund row lock order intake takes before reading pending exposure" is
	 * satisfied by the caller passing a FundRow read under that lock, not by anything in here.
	 * excludeOrderId is always this order's own id: it is still sitting in the quarantine table as
	 * "open" at the moment release reads pending exposure (its own resolution row has not been
	 * inserted yet), so without this it would count its own quantity against itself. actor is
	 * recorded on the DECIDED event this writes -- the releasing supervisor, not the original sender.
	 */
	@Transactional
	public OrderView decideAndRecord(OrderRepository.FundRow fund, long orderId, OrderContext.Side side,
			String ticker, long quantity, BigDecimal referencePrice, String actor, Instant now) {
		Limits limits = limitsRepository.activeLimits();
		OrderContext context = buildContext(fund, side, ticker, quantity, referencePrice, limits, now, orderId);
		runEngineAndStoreDecision(orderId, context, actor, now);
		return buildOrderView(orderId);
	}

	private void runEngineAndStoreDecision(long orderId, OrderContext context, String actor, Instant now) {
		ComplianceEngine.EngineResult result = complianceEngine.evaluate(context);

		orderRepository.insertOrderEvent(orderId, "DECIDED", actor, now, "{}");

		String settingsJson = writeJson(settingsSnapshot(context.limits()));
		String inputsJson = writeJson(inputsSnapshot(context));
		long decisionId = orderRepository.insertDecision(orderId, result.outcome().name(), now, settingsJson,
				inputsJson);
		for (RuleResult ruleResult : result.results()) {
			orderRepository.insertRuleResult(decisionId, ruleResult.ruleName(), ruleResult.outcome().name(),
					ruleResult.reason(), ruleResult.measuredValue(), ruleResult.limitValue());
		}
	}

	/**
	 * One quarantine reason and, for POSSIBLE_DUPLICATE/OPPOSITE_SIDE, the earlier order it matched
	 * (spec 3.3). Package-visible only: an implementation detail of checkQuarantine/recordQuarantine,
	 * never returned from this service.
	 */
	private record QuarantineDecision(String reason, Long matchedOrderId) {
	}

	/**
	 * Spec 3.1/3.3, issue #14: (a) a sender marked out of office right now is quarantined outright;
	 * (b) otherwise, look back LOOKBACK_MINUTES on this fund+security for an order that is not
	 * rejected/expired/cancelled -- same side and quantity within SIMILARITY_PCT is
	 * POSSIBLE_DUPLICATE, opposite side (any quantity) is OPPOSITE_SIDE. This applies regardless of
	 * whether the same person sent both orders ("applies whoever sent the earlier order"): sender
	 * identity is never part of this check, only of the out-of-office check and of who may later
	 * release it.
	 */
	private Optional<QuarantineDecision> checkQuarantine(long fundId, long securityId, String submitterId,
			OrderContext.Side side, long quantity, Instant now, Limits limits) {
		Staff sender = staffRepository.findById(submitterId).orElse(null);
		if (sender != null && isOutOfOffice(sender, now)) {
			return Optional.of(new QuarantineDecision("SENDER_OUT_OF_OFFICE", null));
		}

		long lookbackMinutes = limits.get(LimitKey.LOOKBACK_MINUTES).longValueExact();
		Instant since = now.minus(lookbackMinutes, ChronoUnit.MINUTES);
		BigDecimal similarityPct = limits.get(LimitKey.SIMILARITY_PCT);

		for (OrderRepository.RecentOrderRow candidate : orderRepository.findRecentOrdersForLookback(fundId,
				securityId, since, now)) {
			if (candidate.excludedFromLookback()) {
				continue;
			}
			if (!candidate.side().equals(side.name())) {
				return Optional.of(new QuarantineDecision("OPPOSITE_SIDE", candidate.id()));
			}
			if (isSimilarQuantity(candidate.quantity(), quantity, similarityPct)) {
				return Optional.of(new QuarantineDecision("POSSIBLE_DUPLICATE", candidate.id()));
			}
		}
		return Optional.empty();
	}

	/**
	 * |q2 - q1| x 100 <= SIMILARITY_PCT x q1 (trap #3: never divide before comparing). q1 is the
	 * earlier order's quantity, q2 the new one.
	 */
	private boolean isSimilarQuantity(long q1, long q2, BigDecimal similarityPct) {
		BigDecimal difference = BigDecimal.valueOf(Math.abs(q2 - q1)).multiply(new BigDecimal("100"));
		BigDecimal bound = similarityPct.multiply(BigDecimal.valueOf(q1));
		return difference.compareTo(bound) <= 0;
	}

	/** From <= now < until (same reading LimitChangeService.isOutOfOffice uses). */
	private boolean isOutOfOffice(Staff staff, Instant now) {
		return staff.getOutOfOfficeFrom() != null && staff.getOutOfOfficeUntil() != null
				&& !now.isBefore(staff.getOutOfOfficeFrom()) && now.isBefore(staff.getOutOfOfficeUntil());
	}

	/**
	 * Writes the quarantine row, its QUARANTINED event, and its escalation assignment (spec 3.3):
	 * eligible releasers are SUPERVISORs who are not the sender, not the sender's own backup (the
	 * backup is deliberately held out of this general pool and checked next -- see the class
	 * Javadoc-style note below), and not out of office. If that pool is empty, the sender's backup
	 * is assigned if in office; failing that, any in-office COMPLIANCE user; failing that, the
	 * quarantine is recorded unassigned. Nothing is sent (spec 3.3): this only ever writes a row.
	 *
	 * <p>
	 * Design note on the backup exclusion: if the backup were left in the general SUPERVISOR pool,
	 * an in-office backup who also happens to be a plain SUPERVISOR would already satisfy "eligible
	 * releasers is non-empty", so the backup branch below could never fire while its own named
	 * backup is in office -- the one case the spec describes it for. Holding the backup out of the
	 * general pool and checking them second is what makes the escalation hierarchy in spec 3.3
	 * (general pool, then backup, then compliance, then unassigned) actually reachable in that order.
	 */
	private void recordQuarantine(long orderId, String submitterId, Instant now, Limits limits,
			QuarantineDecision decision) {
		String assignedTo = resolveAssignee(submitterId, now);

		long expiryMinutes = limits.get(LimitKey.QUARANTINE_EXPIRY_MINUTES).longValueExact();
		Instant expiresAt = now.plus(expiryMinutes, ChronoUnit.MINUTES);

		orderRepository.insertOrderEvent(orderId, "QUARANTINED", submitterId, now, "{}");
		orderRepository.insertQuarantine(orderId, decision.reason(), decision.matchedOrderId(), now, expiresAt,
				assignedTo);
	}

	private String resolveAssignee(String submitterId, Instant now) {
		Optional<Staff> sender = staffRepository.findById(submitterId);
		String backupId = sender.map(Staff::getBackupStaff).map(Staff::getId).orElse(null);

		List<Staff> supervisors = staffRepository.findByRoleOrderById("SUPERVISOR");
		boolean eligibleReleaserInOffice = supervisors.stream()
			.filter(s -> !s.getId().equals(submitterId))
			.filter(s -> backupId == null || !s.getId().equals(backupId))
			.anyMatch(s -> !isOutOfOffice(s, now));
		if (eligibleReleaserInOffice) {
			return null;
		}

		if (backupId != null) {
			Staff backup = staffRepository.findById(backupId).orElse(null);
			if (backup != null && !isOutOfOffice(backup, now)) {
				return backupId;
			}
		}

		return staffRepository.findByRoleOrderById("COMPLIANCE")
			.stream()
			.filter(s -> !isOutOfOffice(s, now))
			.map(Staff::getId)
			.findFirst()
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public OrderView getOrder(long id) {
		orderRepository.findOrder(id).orElseThrow(() -> OrdersProblems.notFound("no order with id " + id));
		return buildOrderView(id);
	}

	@Transactional(readOnly = true)
	public PagedOrders getOrdersForFund(long fundId, int page) {
		orderRepository.findFund(fundId).orElseThrow(() -> OrdersProblems.notFound("no fund with id " + fundId));
		int safePage = Math.max(page, 0);
		List<OrderRepository.OrderRow> rows = orderRepository.findOrdersByFund(fundId, PAGE_SIZE, safePage * PAGE_SIZE);
		long total = orderRepository.countOrdersByFund(fundId);
		List<OrderView> views = rows.stream().map(row -> buildOrderView(row.id())).toList();
		return new PagedOrders(views, safePage, PAGE_SIZE, total);
	}

	@Transactional
	public OrderView fill(long orderId, String actorId) {
		return recordTerminalEvent(orderId, actorId, "FILLED");
	}

	@Transactional
	public OrderView cancel(long orderId, String actorId) {
		return recordTerminalEvent(orderId, actorId, "CANCELLED");
	}

	/**
	 * Fill/cancel only from a PASS/REVIEW-decided, not-already-filled-or-cancelled order (spec 3.6);
	 * anything else is a 409, never a 500. Once this event is recorded, the order stops counting as
	 * pending exposure (OrderRepository.findPendingOrders excludes any order with a FILLED or
	 * CANCELLED event). The holdings side of a fill (crediting the security into the fund's
	 * holdings, debiting fund.cash) is out of scope for M7a -- fund.cash and holding rows are left
	 * untouched; only the order's own pending-exposure contribution is removed, which is exactly the
	 * cash rule's existing "value already fits under cash minus pending buys" semantics.
	 */
	private OrderView recordTerminalEvent(long orderId, String actorId, String eventType) {
		orderRepository.findOrder(orderId).orElseThrow(() -> OrdersProblems.notFound("no order with id " + orderId));

		String latestEvent = orderRepository.findLatestEventType(orderId)
			.orElseThrow(() -> new IllegalStateException("order " + orderId + " has no events at all"));

		if (TERMINAL_EVENT_TYPES.contains(latestEvent)) {
			throw OrdersProblems
				.conflict("order " + orderId + " is already " + latestEvent.toLowerCase(Locale.ROOT) + ".");
		}
		if (!"DECIDED".equals(latestEvent)) {
			// M7b statuses (QUARANTINED and friends): not decided yet, so neither fill nor cancel
			// applies.
			throw OrdersProblems.conflict("order " + orderId + " has not been decided yet (" + latestEvent + ").");
		}

		String outcome = orderRepository.findDecisionByOrderId(orderId).orElseThrow().outcome();
		if ("BLOCK".equals(outcome)) {
			throw OrdersProblems.conflict("order " + orderId + " was BLOCKed and cannot be filled or cancelled.");
		}

		orderRepository.insertOrderEvent(orderId, eventType, actorId, clock.instant(), "{}");
		return buildOrderView(orderId);
	}

	private OrderView replay(OrderRepository.OrderRow existing, String hash) {
		if (!existing.requestHash().equals(hash)) {
			throw OrdersProblems.conflict(
					"clientOrderId " + existing.clientOrderId() + " was already used with a different order body.");
		}
		return buildOrderView(existing.id());
	}

	private OrderView buildOrderView(long orderId) {
		OrderRepository.OrderRow order = orderRepository.findOrder(orderId).orElseThrow();
		String status = deriveStatus(orderId);
		DecisionView decision = orderRepository.findDecisionByOrderId(orderId).map(this::toDecisionView).orElse(null);
		QuarantineSummaryView quarantine = orderRepository.findQuarantineByOrderId(orderId)
			.map(this::toQuarantineView)
			.orElse(null);
		return new OrderView(order.id(), order.clientOrderId(), order.fundId(), order.side(), order.ticker(),
				order.quantity(), order.referencePrice(), order.submittedBy(), order.submittedAt(), status, decision,
				quarantine);
	}

	private QuarantineSummaryView toQuarantineView(OrderRepository.QuarantineRow row) {
		return new QuarantineSummaryView(row.reason(), row.matchedOrderId(), row.assignedTo(), row.quarantinedAt(),
				row.expiresAt());
	}

	/**
	 * DECIDED/FILLED/CANCELLED are unchanged from M7a. QUARANTINED (issue #14) is the one status
	 * that is never simply "the latest event": every read has to treat an overdue, still-open
	 * quarantine as EXPIRED itself, on the clock alone, rather than waiting for QuarantineExpiryJob
	 * to write that row (spec 3.3, "correctness never depends on the job's timing") -- see
	 * OrderRepository.findOpenQuarantineExpiry. Once release (RELEASED then DECIDED) or reject
	 * (REJECTED) or the job (EXPIRED) has actually written its event, that event is the latest one
	 * and this method never reaches the quarantine branch below for that order again.
	 */
	private String deriveStatus(long orderId) {
		String latestEvent = orderRepository.findLatestEventType(orderId)
			.orElseThrow(() -> new IllegalStateException("order " + orderId + " has no events at all"));
		if (TERMINAL_EVENT_TYPES.contains(latestEvent)) {
			return latestEvent;
		}
		if ("DECIDED".equals(latestEvent)) {
			return orderRepository.findDecisionByOrderId(orderId).orElseThrow().outcome();
		}
		if ("QUARANTINED".equals(latestEvent)) {
			boolean overdue = orderRepository.findOpenQuarantineExpiry(orderId)
				.map(expiresAt -> !clock.instant().isBefore(expiresAt))
				.orElse(false);
			return overdue ? "EXPIRED" : "QUARANTINED";
		}
		// REJECTED: reported as-is (RELEASED never appears here -- see the Javadoc above).
		return latestEvent;
	}

	private DecisionView toDecisionView(OrderRepository.DecisionRow decisionRow) {
		List<RuleResultView> ruleResults = orderRepository.findRuleResults(decisionRow.id())
			.stream()
			.map(r -> new RuleResultView(r.ruleName(), r.outcome(), r.reason(), r.measuredValue(), r.limitValue()))
			.toList();
		return new DecisionView(decisionRow.outcome(), decisionRow.decidedAt(), ruleResults,
				readJson(decisionRow.settingsSnapshot()), readJson(decisionRow.inputsSnapshot()));
	}

	/**
	 * excludeOrderId leaves one order's own row out of its own pending-exposure read -- see
	 * OrderRepository.findPendingOrders and decideAndRecord's Javadoc. submit() passes -1 (no real
	 * order has that id, and the order being submitted is not yet inserted at the point this is
	 * called anyway).
	 */
	private OrderContext buildContext(OrderRepository.FundRow fund, OrderContext.Side side, String ticker,
			long quantity, BigDecimal referencePrice, Limits limits, Instant now, long excludeOrderId) {
		Map<String, Long> holdings = orderRepository.findHoldings(fund.id());
		Map<String, OrderContext.SecurityInfo> securities = orderRepository.findAllSecurityInfo();
		Set<String> restricted = orderRepository.findRestrictedTickers();
		List<OrderContext.PendingOrder> pendingOrders = orderRepository.findPendingOrders(fund.id(), now,
				excludeOrderId);

		OrderContext.Fund contextFund = new OrderContext.Fund(fund.totalAssets(), fund.cash(), fund.diversified());
		OrderContext.Order order = new OrderContext.Order(side, ticker, quantity, referencePrice);
		return new OrderContext(contextFund, order, holdings, securities, restricted, pendingOrders, limits);
	}

	private Map<String, Object> settingsSnapshot(Limits limits) {
		Map<String, Object> snapshot = new TreeMap<>();
		for (LimitKey key : LimitKey.values()) {
			snapshot.put(key.name(), limits.get(key));
		}
		return snapshot;
	}

	/**
	 * Everything the engine actually read for this decision: fund state, the order, the securities
	 * touched, holdings and pending orders. Read back later (DecisionView.inputsSnapshot) instead of
	 * recomputed, so a later change to holdings, prices or pending orders never alters what a past
	 * decision is shown to have used.
	 */
	private Map<String, Object> inputsSnapshot(OrderContext context) {
		Map<String, Object> fund = new LinkedHashMap<>();
		fund.put("totalAssets", context.fund().totalAssets());
		fund.put("cash", context.fund().cash());
		fund.put("diversified", context.fund().diversified());

		Map<String, Object> order = new LinkedHashMap<>();
		order.put("side", context.order().side().name());
		order.put("ticker", context.order().security());
		order.put("quantity", context.order().quantity());
		order.put("referencePrice", context.order().referencePrice());

		List<Map<String, Object>> pendingOrders = new ArrayList<>();
		for (OrderContext.PendingOrder pending : context.pendingOrders()) {
			Map<String, Object> pendingView = new LinkedHashMap<>();
			pendingView.put("side", pending.side().name());
			pendingView.put("ticker", pending.security());
			pendingView.put("quantity", pending.quantity());
			pendingView.put("price", pending.price());
			pendingOrders.add(pendingView);
		}

		// Only the securities this decision actually touched (the order, holdings, pending orders),
		// not the whole reference table, so the snapshot stays a record of what was used rather than
		// a dump of everything that happened to be loaded.
		Set<String> relevantTickers = new TreeSet<>();
		relevantTickers.add(context.order().security());
		relevantTickers.addAll(context.holdings().keySet());
		context.pendingOrders().forEach(p -> relevantTickers.add(p.security()));
		Map<String, Object> securitiesUsed = new TreeMap<>();
		for (String ticker : relevantTickers) {
			OrderContext.SecurityInfo info = context.securityInfo(ticker);
			Map<String, Object> securityView = new LinkedHashMap<>();
			securityView.put("issuer", info.issuer());
			securityView.put("price", info.price());
			securityView.put("votingSharesOutstanding", info.votingSharesOutstanding());
			securityView.put("avgDailyVolume", info.avgDailyVolume());
			securitiesUsed.put(ticker, securityView);
		}

		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("fund", fund);
		snapshot.put("order", order);
		snapshot.put("holdings", new TreeMap<>(context.holdings()));
		snapshot.put("pendingOrders", pendingOrders);
		snapshot.put("securities", securitiesUsed);
		return snapshot;
	}

	private List<OrdersProblems.FieldError> validate(OrderRequestBody body) {
		List<OrdersProblems.FieldError> errors = new ArrayList<>();
		if (body == null) {
			errors.add(new OrdersProblems.FieldError("body", "a request body is required."));
			return errors;
		}
		if (isBlank(body.clientOrderId())) {
			errors.add(new OrdersProblems.FieldError("clientOrderId", "clientOrderId is required."));
		}
		if (body.fundId() == null) {
			errors.add(new OrdersProblems.FieldError("fundId", "fundId is required."));
		}
		if (isBlank(body.side()) || !isValidSide(body.side())) {
			errors.add(new OrdersProblems.FieldError("side", "side must be BUY or SELL."));
		}
		if (isBlank(body.ticker())) {
			errors.add(new OrdersProblems.FieldError("ticker", "ticker is required."));
		}
		if (body.quantity() == null || body.quantity() <= 0) {
			errors.add(new OrdersProblems.FieldError("quantity", "quantity must be a positive number."));
		}
		return errors;
	}

	private boolean isValidSide(String side) {
		try {
			OrderContext.Side.valueOf(side.trim().toUpperCase(Locale.ROOT));
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * A SHA-256 hex digest of the normalised request fields, stored in trade_order.request_hash and
	 * compared on replay -- cheaper than storing and comparing the raw body, and immune to key
	 * ordering or whitespace differences between two JSON encodings of the "same" request.
	 */
	private String canonicalHash(String clientOrderId, long fundId, OrderContext.Side side, String ticker,
			long quantity) {
		String canonical = clientOrderId + '|' + fundId + '|' + side.name() + '|' + ticker + '|' + quantity;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private String writeJson(Map<String, Object> value) {
		return objectMapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readJson(String json) {
		if (json == null) {
			return Map.of();
		}
		return objectMapper.readValue(json, Map.class);
	}

}
