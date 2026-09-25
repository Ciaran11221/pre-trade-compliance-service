package io.github.ciaran11221.compliance.me;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.ciaran11221.compliance.reference.Staff;
import io.github.ciaran11221.compliance.reference.StaffRepository;

/**
 * The first route in the route-access matrix (see route-access.csv). Any authenticated role may
 * call it -- KnownStaffAuthorizationManager has already turned away an unrecognised staff id
 * before a request gets here.
 */
@RestController
public class MeController {

	private final StaffRepository staffRepository;

	private final Clock clock;

	public MeController(StaffRepository staffRepository, Clock clock) {
		this.staffRepository = staffRepository;
		this.clock = clock;
	}

	@GetMapping("/api/me")
	@PreAuthorize("isAuthenticated()")
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		String staffId = jwt.getSubject();
		Staff staff = staffRepository.findById(staffId)
			// Defensive only: KnownStaffAuthorizationManager already rejects an unknown sub with a
			// 403 before the request reaches this method.
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "unknown staff member"));
		List<String> roles = jwt.getClaimAsStringList("roles");
		Instant now = clock.instant();
		boolean outOfOffice = staff.getOutOfOfficeFrom() != null && staff.getOutOfOfficeUntil() != null
				&& !now.isBefore(staff.getOutOfOfficeFrom()) && !now.isAfter(staff.getOutOfOfficeUntil());
		return new MeResponse(staff.getId(), staff.getName(), staff.getTeam(), roles, outOfOffice);
	}

}
