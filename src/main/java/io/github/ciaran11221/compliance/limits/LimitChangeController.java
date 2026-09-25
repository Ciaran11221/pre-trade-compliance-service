package io.github.ciaran11221.compliance.limits;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The limit-change API: request a change to a firm limit, see one, approve it, or cancel it.
 * Route-access.csv carries a row for every mapping here, checked by RouteAccessMatrixTest.
 */
@RestController
public class LimitChangeController {

	private static final String CHANGE_ROLES = "hasAnyRole('SUPERVISOR','COMPLIANCE','EXECUTIVE')";

	private final LimitChangeService limitChangeService;

	public LimitChangeController(LimitChangeService limitChangeService) {
		this.limitChangeService = limitChangeService;
	}

	@PostMapping("/api/limit-changes")
	@PreAuthorize(CHANGE_ROLES)
	public ResponseEntity<LimitChangeView> requestChange(@AuthenticationPrincipal Jwt jwt,
			// required = false: a missing or empty body must still reach @PreAuthorize before
			// failing, so a disallowed role gets 403 rather than a 400 that races the authorization
			// check (see RouteAccessMatrixTest, which calls every route with no body at all).
			@RequestBody(required = false) LimitChangeRequestBody body) {
		if (body == null) {
			throw LimitChangeProblems.unprocessable("a key, newValue and reason are required.");
		}
		LimitChangeView view = limitChangeService.requestChange(jwt.getSubject(), body);
		return ResponseEntity.status(HttpStatus.CREATED).body(view);
	}

	@GetMapping("/api/limit-changes/{id}")
	@PreAuthorize(CHANGE_ROLES)
	public LimitChangeView getRequest(@PathVariable String id) {
		return limitChangeService.getRequest(parseId(id));
	}

	@PostMapping("/api/limit-changes/{id}/approvals")
	@PreAuthorize(CHANGE_ROLES)
	public LimitChangeView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return limitChangeService.approve(parseId(id), jwt.getSubject(), jwt.getClaimAsStringList("roles"));
	}

	@PostMapping("/api/limit-changes/{id}/cancel")
	@PreAuthorize(CHANGE_ROLES)
	public LimitChangeView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return limitChangeService.cancel(parseId(id), jwt.getSubject(), jwt.getClaimAsStringList("roles"));
	}

	// A String path variable, parsed by hand, rather than @PathVariable long: a long parameter
	// fails argument resolution -- a 400, before @PreAuthorize ever runs -- on any non-numeric id,
	// which is exactly what RouteAccessMatrixTest sends (the literal route-access.csv path,
	// "{id}" included, to every role) to prove a disallowed role gets 403 from every route.
	private long parseId(String id) {
		try {
			return Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			throw LimitChangeProblems.notFound("no limit change request with id " + id);
		}
	}

	@GetMapping("/api/limits")
	@PreAuthorize("isAuthenticated()")
	public Map<String, BigDecimal> activeLimits() {
		return limitChangeService.activeLimits();
	}

}
