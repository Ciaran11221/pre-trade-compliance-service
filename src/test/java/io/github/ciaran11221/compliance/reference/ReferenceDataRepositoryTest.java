package io.github.ciaran11221.compliance.reference;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional keeps the persistence context open for the lazy fund/security
// associations on Holding, read here across several repository calls per test.
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@Transactional
class ReferenceDataRepositoryTest {

	@Autowired
	private FundRepository fundRepository;

	@Autowired
	private ListedSecurityRepository securityRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private StaffRepository staffRepository;

	@Test
	void harbourGrowthFundLoadsWithSeededTotalAssets() {
		Fund hgf = fundRepository.findByCode("HGF").orElseThrow();

		assertThat(hgf.getTotalAssets()).isEqualByComparingTo(new BigDecimal("1000000000.0000"));
	}

	@Test
	void harbourGrowthFundKstlHoldingIsFourHundredFiftyThousandShares() {
		Fund hgf = fundRepository.findByCode("HGF").orElseThrow();
		ListedSecurity kstl = securityRepository.findByTicker("KSTL").orElseThrow();

		Holding holding = holdingRepository.findByFund_Code("HGF").stream()
			.filter(h -> h.getSecurity().getId().equals(kstl.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(holding.getFund().getId()).isEqualTo(hgf.getId());
		assertThat(holding.getQuantity()).isEqualTo(450_000L);
	}

	@Test
	void harbourGrowthFundPositionsAboveFivePercentTotalOneHundredFiftyMillion() {
		Fund hgf = fundRepository.findByCode("HGF").orElseThrow();
		BigDecimal fivePercentThreshold = hgf.getTotalAssets()
			.multiply(new BigDecimal("0.05"));

		List<Holding> holdings = holdingRepository.findByFund_Code("HGF");

		BigDecimal aboveThresholdTotal = holdings.stream()
			.map(h -> h.getSecurity().getPrice().multiply(BigDecimal.valueOf(h.getQuantity())))
			.filter(value -> value.compareTo(fivePercentThreshold) > 0)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		assertThat(aboveThresholdTotal).isEqualByComparingTo(new BigDecimal("150000000.0000"));
	}

	@Test
	void staffRepositoryLoadsSeededStaff() {
		assertThat(staffRepository.findById("anne")).isPresent();
		assertThat(staffRepository.findById("anne").orElseThrow().getBackupStaff().getId()).isEqualTo("sup-2");
	}

}
