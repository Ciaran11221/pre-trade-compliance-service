package io.github.ciaran11221.compliance.limits;

import java.time.Instant;

public record ApprovalView(String approver, String approverTeam, String approverRoles, Instant approvedAt) {
}
