package io.github.ciaran11221.compliance.reference;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<Staff, String> {

	/**
	 * Every staff member holding this role, ordered by id for a deterministic pick when escalation
	 * (M7b, issue #14) needs to choose one in-office COMPLIANCE user from possibly several.
	 */
	List<Staff> findByRoleOrderById(String role);

}
