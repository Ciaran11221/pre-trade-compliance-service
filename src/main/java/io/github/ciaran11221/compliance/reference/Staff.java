package io.github.ciaran11221.compliance.reference;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff")
public class Staff {

	@Id
	private String id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String team;

	// M7b (issue #14): the fact of a role, on the person, not on any one token -- see
	// V5__staff_role.sql's javadoc-style comment for why escalation needs this and token roles
	// cannot supply it. One of TRADER, SUPERVISOR, COMPLIANCE, EXECUTIVE, or null when not recorded
	// (never chosen by escalation).
	private String role;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "backup_staff_id")
	private Staff backupStaff;

	@Column(name = "out_of_office_from")
	private Instant outOfOfficeFrom;

	@Column(name = "out_of_office_until")
	private Instant outOfOfficeUntil;

	protected Staff() {
	}

	public Staff(String id, String name, String team, String role, Staff backupStaff, Instant outOfOfficeFrom,
			Instant outOfOfficeUntil) {
		this.id = id;
		this.name = name;
		this.team = team;
		this.role = role;
		this.backupStaff = backupStaff;
		this.outOfOfficeFrom = outOfOfficeFrom;
		this.outOfOfficeUntil = outOfOfficeUntil;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getTeam() {
		return team;
	}

	public String getRole() {
		return role;
	}

	public Staff getBackupStaff() {
		return backupStaff;
	}

	public Instant getOutOfOfficeFrom() {
		return outOfOfficeFrom;
	}

	public Instant getOutOfOfficeUntil() {
		return outOfOfficeUntil;
	}

	/**
	 * Staff is mutable reference data (spec 3.7), unlike every audit table -- setters here back
	 * PUT /api/staff/{id}/out-of-office (M7b, issue #14), a plain UPDATE through JPA's own dirty
	 * checking on a managed entity, rather than a raw JdbcTemplate statement (StaffRepository is a
	 * JpaRepository; there is no insert-only repository pattern to match here).
	 */
	public void setOutOfOffice(Instant outOfOfficeFrom, Instant outOfOfficeUntil) {
		this.outOfOfficeFrom = outOfOfficeFrom;
		this.outOfOfficeUntil = outOfOfficeUntil;
	}

}
