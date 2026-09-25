package io.github.ciaran11221.compliance.reference;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingRepository extends JpaRepository<Holding, HoldingId> {

	List<Holding> findByFund_Code(String fundCode);

}
