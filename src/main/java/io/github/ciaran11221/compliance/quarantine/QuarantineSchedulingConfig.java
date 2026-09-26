package io.github.ciaran11221.compliance.quarantine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's @Scheduled processing for QuarantineExpiryJob. Off in the test profile
 * (compliance.quarantine.expiry-job.enabled: false in application-test.yml) so no test ever races
 * a background trigger of runExpiry() -- tests call it directly instead. QuarantineExpiryJob
 * itself is still a plain bean either way, so it stays autowirable and callable by hand in tests
 * regardless of this flag; only the automatic @Scheduled firing is gated.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "compliance.quarantine.expiry-job.enabled", havingValue = "true", matchIfMissing = true)
public class QuarantineSchedulingConfig {
}
