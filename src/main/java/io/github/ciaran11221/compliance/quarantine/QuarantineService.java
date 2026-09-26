package io.github.ciaran11221.compliance.quarantine;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ciaran11221.compliance.orders.OrderRepository;
import io.github.ciaran11221.compliance.orders.OrderService;
import io.github.ciaran11221.compliance.orders.OrderView;
import io.github.ciaran11221.compliance.reference.Staff;
import io.github.ciaran11221.compliance.reference.StaffRepository;
import io.github.ciaran11221.compliance.rules.OrderContext;

/**
 * Spec 3.3's release/reject/list, plus the expiry job's per-row work. This package depends on
 * orders (OrderRepository.lockFund/findOrder, and OrderService.decideAndRecord for "reuse, don't
 * duplicate, the decision-storing code") in one direction only -- orders never depends back on
 * this package, so there is no Spring bean cycle (see QuarantineRepository's Javadoc for how the
 * two repositories share tables without a Java dependency either way).
 */
@Service
public class QuarantineService {

	private final QuarantineRepository quarantineRepository;

	private final OrderRepository orderRepository;

	private final OrderService orderService;

	private final StaffRepository staffRepository;

	private final Clock clock;

	public QuarantineService(QuarantineRepository quarantineRepository, OrderRepository orderRepository,
			OrderService orderService, StaffRepository staffRepository, Clock clock) {
		this.quarantineRepository = quarantineRepository;
		this.orderRepository = orderRepository;
		this.orderService = orderService;
		this.staffRepository = staffRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<QuarantineListItemView> list(boolean assignedToMe, String callerId) {
		Instant now = clock.instant();
		String assignedTo = assignedToMe ? callerId : null;
		return quarantineRepository.findOpenQuarantines(now, assignedTo)
			.stream()
			.map(this::toListItemView)
			.toList();
	}

	private QuarantineListItemView toListItemView(QuarantineRepository.OpenQuarantineRow row) {
		return new QuarantineListItemView(row.orderId(), row.fundId(), row.ticker(), row.side(), row.quantity(),
				row.submittedBy(), row.reason(), row.matchedOrderId(), row.quarantinedAt(), row.expiresAt(),
				row.assignedTo());
	}

	/**
	 * Spec 3.3: release runs the compliance rules, then records the decision, exactly the way order
	 * intake does (OrderService.decideAndRecord). 403 if the caller is the sender or out of office;
	 * 409 if already resolved or expired. Trap #7: the fund lock is taken (OrderRepository.lockFund)
	 * -- the SAME lock order intake takes -- before decideAndRecord reads pending exposure, so a
	 * release and a new order for the same fund can never both spend the same cash; see
	 * QuarantineReleaseFundLockRaceTest.
	 */
	@Transactional
	public OrderView release(long orderId, String callerId) {
		OrderRepository.QuarantineRow quarantine = lockOpenQuarantine(orderId, callerId);
		OrderRepository.OrderRow order = orderRepository.findOrder(orderId).orElseThrow();

		OrderRepository.FundRow fund = orderRepository.lockFund(order.fundId()).orElseThrow();
		Instant now = clock.instant();

		orderRepository.insertOrderEvent(orderId, "RELEASED", callerId, now, "{}");

		boolean won = quarantineRepository.insertResolutionIfAbsent(quarantine.id(), "RELEASED", callerId, now);
		if (!won) {
			// Lost the race to another release/reject/the expiry job between the lock above and here
			// -- unreachable in practice (the FOR UPDATE lock on the quarantine row is held for the
			// rest of this transaction), kept as a defensive 409 rather than ever proceeding to
			// decide an order someone else just resolved.
			throw QuarantineProblems.conflict("order " + orderId + "'s quarantine was just resolved by someone else.");
		}

		return orderService.decideAndRecord(fund, orderId, OrderContext.Side.valueOf(order.side()), order.ticker(),
				order.quantity(), order.referencePrice(), callerId, now);
	}

	/** Spec 3.3: reject is the same eligibility rules as release, with no decision recorded. */
	@Transactional
	public OrderView reject(long orderId, String callerId) {
		OrderRepository.QuarantineRow quarantine = lockOpenQuarantine(orderId, callerId);
		Instant now = clock.instant();

		orderRepository.insertOrderEvent(orderId, "REJECTED", callerId, now, "{}");

		boolean won = quarantineRepository.insertResolutionIfAbsent(quarantine.id(), "REJECTED", callerId, now);
		if (!won) {
			throw QuarantineProblems.conflict("order " + orderId + "'s quarantine was just resolved by someone else.");
		}

		return orderService.getOrder(orderId);
	}

	/**
	 * Common release/reject preamble: lock the quarantine row (spec 3.3's concurrency done-when
	 * item), then check it exists, is not already resolved, is not overdue, the caller is not the
	 * sender, and the caller is not out of office -- in that order, so a caller who fails more than
	 * one check always gets the same response for the same input.
	 */
	private OrderRepository.QuarantineRow lockOpenQuarantine(long orderId, String callerId) {
		OrderRepository.OrderRow order = orderRepository.findOrder(orderId)
			.orElseThrow(() -> QuarantineProblems.notFound("no order with id " + orderId));

		OrderRepository.QuarantineRow quarantine = quarantineRepository.lockQuarantineByOrderId(orderId)
			.orElseThrow(() -> QuarantineProblems.conflict("order " + orderId + " is not quarantined."));

		if (quarantineRepository.findResolution(quarantine.id()).isPresent()) {
			throw QuarantineProblems.conflict("order " + orderId + "'s quarantine has already been resolved.");
		}

		Instant now = clock.instant();
		if (!now.isBefore(quarantine.expiresAt())) {
			throw QuarantineProblems.conflict("order " + orderId + "'s quarantine expired at " + quarantine.expiresAt()
					+ " and can no longer be released or rejected.");
		}

		if (callerId.equals(order.submittedBy())) {
			throw QuarantineProblems.forbidden("the sender of an order may not release or reject its own quarantine.");
		}

		Staff caller = staffRepository.findById(callerId).orElse(null);
		if (caller != null && isOutOfOffice(caller, now)) {
			throw QuarantineProblems.forbidden(callerId + " is marked out of office and may not release or reject.");
		}

		return quarantine;
	}

	/** Same reading OrderService/LimitChangeService use: from <= now < until. */
	private boolean isOutOfOffice(Staff staff, Instant now) {
		return staff.getOutOfOfficeFrom() != null && staff.getOutOfOfficeUntil() != null
				&& !now.isBefore(staff.getOutOfOfficeFrom()) && now.isBefore(staff.getOutOfOfficeUntil());
	}

	// ---- QuarantineExpiryJob's per-row work ----

	/** Plain read, no transaction needed: QuarantineExpiryJob loops over these calling expireOne. */
	public List<Long> findOverdueOrderIds() {
		return quarantineRepository.findOverdueUnresolvedOrderIds(clock.instant());
	}

	/**
	 * Writes the EXPIRED resolution for one overdue, still-open quarantine (spec 3.3), and the
	 * matching order_event -- idempotent (ON CONFLICT DO NOTHING inside insertResolutionIfAbsent),
	 * and safe against a concurrent release/reject: the FOR UPDATE lock below serialises against
	 * lockOpenQuarantine above, so whichever of "the job" and "a release/reject call" gets the lock
	 * first wins, and the loser's insertResolutionIfAbsent (or, here, the resolution-already-present
	 * check) finds nothing left to do. Returns false (a no-op) if the quarantine was already
	 * resolved or is no longer overdue by the time the lock is granted.
	 */
	@Transactional
	public boolean expireOne(long orderId) {
		Optional<OrderRepository.QuarantineRow> quarantine = quarantineRepository.lockQuarantineByOrderId(orderId);
		if (quarantine.isEmpty()) {
			return false;
		}
		if (quarantineRepository.findResolution(quarantine.get().id()).isPresent()) {
			return false;
		}
		Instant now = clock.instant();
		if (now.isBefore(quarantine.get().expiresAt())) {
			return false;
		}
		boolean won = quarantineRepository.insertResolutionIfAbsent(quarantine.get().id(), "EXPIRED", null, now);
		if (!won) {
			return false;
		}
		orderRepository.insertOrderEvent(orderId, "EXPIRED", null, now, "{}");
		return true;
	}

}
