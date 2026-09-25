package io.github.ciaran11221.compliance.reference;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListedSecurityRepository extends JpaRepository<ListedSecurity, Long> {

	Optional<ListedSecurity> findByTicker(String ticker);

}
