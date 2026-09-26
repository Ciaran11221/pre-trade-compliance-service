package io.github.ciaran11221.compliance.quarantine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spec 3.3: "A scheduled job writes it [EXPIRED], and any read treats an overdue quarantine as
 * expired, so correctness never depends on the job's timing." This job is the write side only --
 * every read (OrderService.deriveStatus, findPendingOrders, QuarantineRepository.findOpenQuarantines,
 * OrderService.checkQuarantine's lookback) already computes EXPIRED on the clock alone, so this
 * job's only job is to eventually make that computation permanent as a real order_event +
 * quarantine_resolution row, for a plain audit read that does not want to re-derive it.
 *
 * <p>
 * Scheduling is disabled in the test profile (QuarantineSchedulingConfig is
 * @ConditionalOnProperty, off in application-test.yml) so tests never race a background trigger;
 * runExpiry() is called directly instead (QuarantineExpiryJobTest), which is why it is public and
 * returns a count rather than relying on @Scheduled ever having fired.
 */
@Component
public class QuarantineExpiryJob {

	private final QuarantineService quarantineService;

	public QuarantineExpiryJob(QuarantineService quarantineService) {
		this.quarantineService = quarantineService;
	}

	@Scheduled(fixedDelayString = "${compliance.quarantine.expiry-job.fixed-delay-ms:60000}")
	public int runExpiry() {
		int expired = 0;
		for (Long orderId : quarantineService.findOverdueOrderIds()) {
			if (quarantineService.expireOne(orderId)) {
				expired++;
			}
		}
		return expired;
	}

}
