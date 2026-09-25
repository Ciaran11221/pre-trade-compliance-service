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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "backup_staff_id")
	private Staff backupStaff;

	@Column(name = "out_of_office_from")
	private Instant outOfOfficeFrom;

	@Column(name = "out_of_office_until")
	private Instant outOfOfficeUntil;

	protected Staff() {
	}

	public Staff(String id, String name, String team, Staff backupStaff, Instant outOfOfficeFrom,
			Instant outOfOfficeUntil) {
		this.id = id;
		this.name = name;
		this.team = team;
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

	public Staff getBackupStaff() {
		return backupStaff;
	}

	public Instant getOutOfOfficeFrom() {
		return outOfOfficeFrom;
	}

	public Instant getOutOfOfficeUntil() {
		return outOfOfficeUntil;
	}

}
