package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import tools.jackson.databind.ObjectMapper;

import io.github.ciaran11221.compliance.reference.Staff;
import io.github.ciaran11221.compliance.reference.StaffRepository;
import io.github.ciaran11221.compliance.rules.LimitKey;
import io.github.ciaran11221.compliance.rules.Limits;
import io.github.ciaran11221.compliance.rules.LimitsRepository;

/**
 * Rules 1-11 of the limit-change process: hard bounds, the impact preview, how many approvals a
 * change needs, who may give them, when a change activates, cancellation, staleness, the status a
 * caller sees, and (rule 11) at most one change per setting key waiting to activate at a time.
 * Each public method is one transaction; approve() and cancel() start by locking the request row
 * (LimitChangeRepository.lockRequest), which is what makes rule 9's concurrent-approval race safe
 * -- see LimitChangeConcurrencyTest. requestChange() and approve() also take
 * LimitChangeRepository.lockSettingKey(), a per-key advisory lock, before deciding whether a
 * waiting change exists for that key -- see the Javadoc there for why the row lock alone can't
 * cover two different requests racing on the same key.
 */
@Service
public class LimitChangeService {

	private final LimitsRepository limitsRepository;

	private final LimitChangeRepository limitChangeRepository;

	private final StaffRepository staffRepository;

	private final ImpactPreviewCalculator impactPreviewCalculator;

	private final Clock clock;

	private final ObjectMapper objectMapper;

	public LimitChangeService(LimitsRepository limitsRepository, LimitChangeRepository limitChangeRepository,
			StaffRepository staffRepository, ImpactPreviewCalculator impactPreviewCalculator, Clock clock,
			ObjectMapper objectMapper) {
		this.limitsRepository = limitsRepository;
		this.limitChangeRepository = limitChangeRepository;
		this.staffRepository = staffRepository;
		this.impactPreviewCalculator = impactPreviewCalculator;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	// noRollbackFor: rule 1 records a refused attempt (limit_change_refusal) even though the
	// request itself is refused, and ErrorResponseException is an unchecked exception -- Spring's
	// default rollback-on-any-RuntimeException would otherwise undo that insert the instant this
	// method throws to report the refusal, silently discarding the very row the refusal exists to
	// leave behind. The "nothing to change" branch and the rule-11 waiting-change conflict below
	// both throw the same exception type with nothing yet written (lockSettingKey takes an
	// advisory lock, not a row -- there is nothing for a rollback to undo there either), so telling
	// Spring not to roll back on either of them is a no-op, not a risk.
	@Transactional(noRollbackFor = ErrorResponseException.class)
	public LimitChangeView requestChange(String requesterId, LimitChangeRequestBody body) {
		LimitKey key = parseKey(body.key());
		Instant now = clock.instant();

		// Rule 11: serialise against any other in-flight request or approval for this key before
		// even looking at whether one is waiting -- see LimitChangeRepository.lockSettingKey.
		limitChangeRepository.lockSettingKey(key.name());
		limitChangeRepository.findWaitingChange(key.name(), now, 0L)
			.ifPresent(waiting -> { throw waitingChangeConflict(key.name(), waiting); });

		Limits currentLimits = limitsRepository.activeLimits();
		BigDecimal oldValue = currentLimits.get(key);
		BigDecimal newValue = body.newValue();

		if (newValue.compareTo(oldValue) == 0) {
			throw LimitChangeProblems
				.unprocessable(key.name() + " is already " + plain(oldValue) + "; nothing to change.");
		}

		Optional<LimitKey.Bound> violated = key.firstViolatedBound(newValue);
		if (violated.isPresent()) {
			String reason = violated.get().describe(key);
			limitChangeRepository.insertRefusal(requesterId, key.name(), newValue, reason, now);
			throw LimitChangeProblems.unprocessable(reason);
		}

		LimitChangeDirection direction = key.isLoosening(oldValue, newValue) ? LimitChangeDirection.LOOSEN
				: LimitChangeDirection.TIGHTEN;

		ImpactPreview preview = impactPreviewCalculator.compute(key, newValue, currentLimits);
		int requiredApprovals = RequiredApprovalsCalculator.requiredApprovals(direction, preview,
				currentLimits.get(LimitKey.LARGE_LOOSENING_USD),
				currentLimits.get(LimitKey.LARGE_LOOSENING_PCT_OF_FUNDS));

		long requestId = limitChangeRepository.insertRequest(key.name(), oldValue, newValue, direction.name(),
				body.reason(), requesterId, now);
		limitChangeRepository.insertPreview(requestId, preview.usdNewlyAllowed(), preview.hidesBreach(),
				requiredApprovals, writeJson(preview.detail()));

		return toView(requestId);
	}

	@Transactional(readOnly = true)
	public LimitChangeView getRequest(long id) {
		limitChangeRepository.findRequest(id)
			.orElseThrow(() -> LimitChangeProblems.notFound("no limit change request with id " + id));
		return toView(id);
	}

	@Transactional
	public LimitChangeView approve(long id, String approverId, List<String> roles) {
		Instant now = clock.instant();
		LimitChangeRepository.RequestRow requestRow = limitChangeRepository.lockRequest(id)
			.orElseThrow(() -> LimitChangeProblems.notFound("no limit change request with id " + id));

		// Rule 11: the same per-key advisory lock requestChange() takes, held for the rest of this
		// transaction. lockRequest's FOR UPDATE above only ever covers this one request's row, so
		// two concurrent final approvals on two DIFFERENT requests for the same key -- the race the
		// rule-11 finding turned up -- would otherwise both reach the waiting-change check below (and
		// maybeActivate) without either seeing the other's write. This lock is what makes that
		// impossible: whichever approval gets here second blocks until the first transaction commits
		// or rolls back. See LimitChangeRepository.lockSettingKey.
		limitChangeRepository.lockSettingKey(requestRow.settingKey());

		if (limitChangeRepository.findCancellation(id).isPresent()) {
			throw LimitChangeProblems.conflict("this request has been cancelled.");
		}

		// Rule 11: approving a DIFFERENT request for a key that already has a change waiting to
		// activate is refused -- exactly the gap the finding describes (an urgent tightening
		// silently undone hours later when the earlier loosening's cooling-off ends). Approving the
		// waiting request itself is unaffected: findWaitingChange excludes this id, so a later
		// approver finishing off the very request that is waiting sails through, same as today.
		limitChangeRepository.findWaitingChange(requestRow.settingKey(), now, id)
			.ifPresent(waiting -> { throw waitingChangeConflict(requestRow.settingKey(), waiting); });

		// Rule 7 (stale) only means "some OTHER change moved the active value out from under this
		// one" -- never this request's own activation. Skipping the check once this request has
		// already activated is what lets a second, concurrent final approval (see
		// LimitChangeConcurrentApprovalTest) succeed cleanly instead of being told the setting it
		// itself just set is "not what this request assumed": without this guard, the first
		// approver's insertSettingValue call (inside maybeActivate, below) makes the active value
		// diverge from requestRow.oldValue() the instant it commits, and the second approver --
		// unblocked from the same row lock right after -- would see that as staleness even though
		// nothing but this request's own approvals produced it.
		if (limitChangeRepository.findActivation(id).isEmpty()) {
			BigDecimal activeValue = limitsRepository.activeLimits().get(LimitKey.valueOf(requestRow.settingKey()));
			if (activeValue.compareTo(requestRow.oldValue()) != 0) {
				throw LimitChangeProblems.conflict("stale: " + requestRow.settingKey() + " is now "
						+ plain(activeValue) + ", not " + plain(requestRow.oldValue()) + " as this request assumed.");
			}
		}

		if (approverId.equals(requestRow.requestedBy())) {
			throw LimitChangeProblems.forbidden("the requester may not approve their own request.");
		}

		if (limitChangeRepository.hasApproved(id, approverId)) {
			throw LimitChangeProblems.conflict(approverId + " has already approved this request.");
		}

		Staff approver = staffRepository.findById(approverId)
			.orElseThrow(() -> LimitChangeProblems.forbidden("unknown staff member"));
		if (isOutOfOffice(approver, now)) {
			throw LimitChangeProblems.forbidden(approverId + " is marked out of office and may not approve.");
		}

		limitChangeRepository.insertApproval(id, approverId, approver.getTeam(), String.join(",", roles), now);

		maybeActivate(id, requestRow, now);

		return toView(id);
	}

	@Transactional
	public LimitChangeView cancel(long id, String callerId, List<String> roles) {
		Instant now = clock.instant();
		LimitChangeRepository.RequestRow requestRow = limitChangeRepository.lockRequest(id)
			.orElseThrow(() -> LimitChangeProblems.notFound("no limit change request with id " + id));

		if (limitChangeRepository.findCancellation(id).isPresent()) {
			throw LimitChangeProblems.conflict("this request has already been cancelled.");
		}

		Optional<LimitChangeRepository.ActivationRow> activation = limitChangeRepository.findActivation(id);
		if (activation.isPresent() && !now.isBefore(activation.get().activatesAt())) {
			throw LimitChangeProblems
				.conflict("too late to cancel: this change activated at " + activation.get().activatesAt() + ".");
		}

		boolean isRequester = callerId.equals(requestRow.requestedBy());
		boolean isApprover = limitChangeRepository.findApprovals(id)
			.stream()
			.anyMatch(a -> a.approver().equals(callerId));
		boolean isCompliance = roles.contains("COMPLIANCE");
		if (!isRequester && !isApprover && !isCompliance) {
			throw LimitChangeProblems.forbidden(
					"only the requester, an approver on this request, or a COMPLIANCE user may cancel it.");
		}

		limitChangeRepository.insertCancellation(id, callerId, now);
		return toView(id);
	}

	@Transactional(readOnly = true)
	public Map<String, BigDecimal> activeLimits() {
		Limits limits = limitsRepository.activeLimits();
		Map<String, BigDecimal> values = new TreeMap<>();
		for (LimitKey key : LimitKey.values()) {
			values.put(key.name(), limits.get(key));
		}
		return values;
	}

	/**
	 * Rules 4-6: checks the request is satisfied (enough approvals, at least one from a different
	 * team than the requester, and -- for a large loosening -- at least one COMPLIANCE approver),
	 * then activates it. Idempotent: does nothing if an activation row already exists, which is
	 * what makes it safe for two approvals to both reach this method under the row lock without
	 * ever inserting two activation rows for the same request.
	 */
	private void maybeActivate(long requestId, LimitChangeRepository.RequestRow requestRow, Instant now) {
		if (limitChangeRepository.findActivation(requestId).isPresent()) {
			return;
		}

		LimitChangeRepository.PreviewRow preview = limitChangeRepository.findPreview(requestId).orElseThrow();
		List<LimitChangeRepository.ApprovalRow> approvals = limitChangeRepository.findApprovals(requestId);
		if (approvals.size() < preview.requiredApprovals()) {
			return;
		}

		Staff requester = staffRepository.findById(requestRow.requestedBy()).orElseThrow();
		boolean differentTeam = approvals.stream().anyMatch(a -> !a.approverTeam().equals(requester.getTeam()));
		if (!differentTeam) {
			return;
		}

		boolean large = preview.requiredApprovals() >= 3;
		if (large) {
			boolean complianceApproved = approvals.stream()
				.anyMatch(a -> Arrays.asList(a.approverRoles().split(",")).contains("COMPLIANCE"));
			if (!complianceApproved) {
				return;
			}
		}

		LimitChangeDirection direction = LimitChangeDirection.valueOf(requestRow.direction());
		Instant activatesAt = now;
		if (direction == LimitChangeDirection.LOOSEN && large) {
			BigDecimal coolingOffHours = limitsRepository.activeLimits().get(LimitKey.COOLING_OFF_HOURS);
			activatesAt = now.plus(coolingOffHours.longValueExact(), ChronoUnit.HOURS);
		}

		limitChangeRepository.insertActivation(requestId, activatesAt, now);
		limitChangeRepository.insertSettingValue(requestRow.settingKey(), requestRow.newValue(), activatesAt,
				requestId);
	}

	private LimitChangeView toView(long requestId) {
		LimitChangeRepository.RequestRow requestRow = limitChangeRepository.findRequest(requestId).orElseThrow();
		LimitChangeRepository.PreviewRow previewRow = limitChangeRepository.findPreview(requestId).orElseThrow();
		List<LimitChangeRepository.ApprovalRow> approvals = limitChangeRepository.findApprovals(requestId);
		Optional<LimitChangeRepository.ActivationRow> activation = limitChangeRepository.findActivation(requestId);
		Optional<LimitChangeRepository.CancellationRow> cancellation = limitChangeRepository
			.findCancellation(requestId);

		LimitChangeStatus status = deriveStatus(requestRow, activation, cancellation);

		PreviewView previewView = new PreviewView(previewRow.usdNewlyAllowed(), previewRow.hidesBreach(),
				previewRow.requiredApprovals(), readJson(previewRow.detail()));
		List<ApprovalView> approvalViews = approvals.stream()
			.map(a -> new ApprovalView(a.approver(), a.approverTeam(), a.approverRoles(), a.approvedAt()))
			.toList();

		return new LimitChangeView(requestRow.id(), requestRow.settingKey(), requestRow.oldValue(),
				requestRow.newValue(), requestRow.direction(), requestRow.reason(), requestRow.requestedBy(),
				requestRow.requestedAt(), previewView, approvalViews, status.name(),
				activation.map(LimitChangeRepository.ActivationRow::activatesAt).orElse(null));
	}

	private LimitChangeStatus deriveStatus(LimitChangeRepository.RequestRow requestRow,
			Optional<LimitChangeRepository.ActivationRow> activation,
			Optional<LimitChangeRepository.CancellationRow> cancellation) {
		if (cancellation.isPresent()) {
			return LimitChangeStatus.CANCELLED;
		}
		if (activation.isPresent()) {
			Instant now = clock.instant();
			return now.isBefore(activation.get().activatesAt()) ? LimitChangeStatus.AWAITING_ACTIVATION
					: LimitChangeStatus.ACTIVE;
		}
		BigDecimal activeValue = limitsRepository.activeLimits().get(LimitKey.valueOf(requestRow.settingKey()));
		return activeValue.compareTo(requestRow.oldValue()) == 0 ? LimitChangeStatus.PENDING
				: LimitChangeStatus.STALE;
	}

	/** From <= now < until, per rule 4 -- a person mid-leave whose login may not be their own. */
	private boolean isOutOfOffice(Staff staff, Instant now) {
		return staff.getOutOfOfficeFrom() != null && staff.getOutOfOfficeUntil() != null
				&& !now.isBefore(staff.getOutOfOfficeFrom()) && now.isBefore(staff.getOutOfOfficeUntil());
	}

	/** Rule 11's 409: names the key, the waiting request, and when it activates. */
	private ErrorResponseException waitingChangeConflict(String settingKey,
			LimitChangeRepository.WaitingChangeRow waiting) {
		return LimitChangeProblems.conflict(settingKey + " already has a change waiting to activate (request "
				+ waiting.requestId() + ", activates at " + waiting.activatesAt() + "); cancel it first.");
	}

	private LimitKey parseKey(String key) {
		try {
			return LimitKey.valueOf(key);
		}
		catch (IllegalArgumentException ex) {
			throw LimitChangeProblems.unprocessable("unknown limit key \"" + key + "\".");
		}
	}

	private String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
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
