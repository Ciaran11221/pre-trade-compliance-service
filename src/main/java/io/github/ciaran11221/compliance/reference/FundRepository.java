package io.github.ciaran11221.compliance.reference;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FundRepository extends JpaRepository<Fund, Long> {

	Optional<Fund> findByCode(String code);

}
