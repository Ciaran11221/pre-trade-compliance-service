package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LimitChangeView(long id, String key, BigDecimal oldValue, BigDecimal newValue, String direction,
		String reason, String requestedBy, Instant requestedAt, PreviewView preview, List<ApprovalView> approvals,
		String status, Instant activatesAt) {
}
