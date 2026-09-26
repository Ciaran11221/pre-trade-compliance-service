package io.github.ciaran11221.compliance.reference;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec 3.3/3.6: PUT /api/staff/{id}/out-of-office sets or clears a staff member's out-of-office
 * window. Staff is mutable reference data (spec 3.7), so this is a plain update through JPA's
 * dirty checking on the managed Staff entity, not an insert into an audit table.
 */
@Service
public class StaffService {

	private final StaffRepository staffRepository;

	public StaffService(StaffRepository staffRepository) {
		this.staffRepository = staffRepository;
	}

	@Transactional
	public StaffView setOutOfOffice(String id, OutOfOfficeRequestBody body) {
		Staff staff = staffRepository.findById(id)
			.orElseThrow(() -> ReferenceProblems.notFound("no staff with id " + id));

		String from = body != null ? body.from() : null;
		String until = body != null ? body.until() : null;
		if ((from == null) != (until == null)) {
			throw ReferenceProblems
				.badRequest("from and until must both be set, or both be absent/null (to clear), never just one.");
		}

		if (from == null) {
			staff.setOutOfOffice(null, null);
		}
		else {
			Instant fromInstant = parseInstant("from", from);
			Instant untilInstant = parseInstant("until", until);
			if (!fromInstant.isBefore(untilInstant)) {
				throw ReferenceProblems.badRequest("from must be strictly before until.");
			}
			staff.setOutOfOffice(fromInstant, untilInstant);
		}

		return toView(staff);
	}

	private Instant parseInstant(String field, String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw ReferenceProblems.badRequest(field + " must be an ISO-8601 instant, e.g. 2026-01-01T00:00:00Z.");
		}
	}

	private StaffView toView(Staff staff) {
		return new StaffView(staff.getId(), staff.getName(), staff.getTeam(), staff.getRole(),
				staff.getOutOfOfficeFrom(), staff.getOutOfOfficeUntil());
	}

}
