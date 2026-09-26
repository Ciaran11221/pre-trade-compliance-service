package io.github.ciaran11221.compliance.reference;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUT /api/staff/{id}/out-of-office (spec 3.3/3.6): self, or a SUPERVISOR, may set or clear a
 * staff member's out-of-office window. "Self" cannot be expressed as a role in route-access.csv,
 * so it is expressed directly in @PreAuthorize here; the CSV row for this route lists SUPERVISOR
 * only, which is exactly what RouteAccessMatrixTest's per-role sweep needs to stay correct (it
 * calls every route with a path variable that is never the signed-in caller's own id, so "self"
 * never fires in that test -- see StaffOutOfOfficeAccessTest for the self-allowed/other-forbidden
 * behaviour this expression actually adds).
 */
@RestController
public class StaffController {

	private final StaffService staffService;

	public StaffController(StaffService staffService) {
		this.staffService = staffService;
	}

	@PutMapping("/api/staff/{id}/out-of-office")
	@PreAuthorize("#id == authentication.name or hasRole('SUPERVISOR')")
	public StaffView setOutOfOffice(@PathVariable String id,
			// required = false: a missing body must still reach @PreAuthorize before failing, same
			// reasoning as OrderController/LimitChangeController (RouteAccessMatrixTest calls every
			// route with no body at all).
			@RequestBody(required = false) OutOfOfficeRequestBody body) {
		return staffService.setOutOfOffice(id, body);
	}

}
