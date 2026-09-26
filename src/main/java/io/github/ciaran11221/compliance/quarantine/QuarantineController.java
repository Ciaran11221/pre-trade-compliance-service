package io.github.ciaran11221.compliance.quarantine;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ciaran11221.compliance.orders.OrderView;

/**
 * The quarantine API (spec 3.3/3.6): the open queue, release and reject. Route-access.csv carries
 * a row for every mapping here, checked by RouteAccessMatrixTest.
 */
@RestController
public class QuarantineController {

	private final QuarantineService quarantineService;

	public QuarantineController(QuarantineService quarantineService) {
		this.quarantineService = quarantineService;
	}

	@GetMapping("/api/quarantine")
	@PreAuthorize("hasAnyRole('SUPERVISOR','COMPLIANCE')")
	public List<QuarantineListItemView> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "false") boolean assignedToMe) {
		return quarantineService.list(assignedToMe, jwt.getSubject());
	}

	// Path variable is named "{id}", matching every other controller in this codebase (OrderController,
	// LimitChangeController), rather than "{orderId}": RouteAccessMatrixTest's substitution helper
	// only knows "{id}" and "{fundId}" as literal placeholders to fill in when it calls every route
	// with every role, and a route this test cannot even reach would defeat the point of it.
	@PostMapping("/api/quarantine/{id}/release")
	@PreAuthorize("hasRole('SUPERVISOR')")
	public OrderView release(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return quarantineService.release(parseId(id), jwt.getSubject());
	}

	@PostMapping("/api/quarantine/{id}/reject")
	@PreAuthorize("hasRole('SUPERVISOR')")
	public OrderView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return quarantineService.reject(parseId(id), jwt.getSubject());
	}

	// Same reasoning as OrderController/LimitChangeController's parseId: a String path variable
	// parsed by hand so a non-numeric id fails with a 404 this controller throws, rather than a 400
	// from argument resolution that would race @PreAuthorize's 403 -- see RouteAccessMatrixTest.
	private long parseId(String id) {
		try {
			return Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			throw QuarantineProblems.notFound("no such order: " + id);
		}
	}

}
