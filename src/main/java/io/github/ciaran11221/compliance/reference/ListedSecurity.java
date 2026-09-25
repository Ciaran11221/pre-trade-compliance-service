package io.github.ciaran11221.compliance.reference;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "security")
public class ListedSecurity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String ticker;

	@Column(nullable = false)
	private String name;

	@Column(name = "issuer_name", nullable = false)
	private String issuerName;

	@Column(nullable = false)
	private BigDecimal price;

	@Column(name = "voting_shares_outstanding", nullable = false)
	private Long votingSharesOutstanding;

	@Column(name = "avg_daily_volume", nullable = false)
	private Long avgDailyVolume;

	protected ListedSecurity() {
	}

	public ListedSecurity(String ticker, String name, String issuerName, BigDecimal price,
			Long votingSharesOutstanding, Long avgDailyVolume) {
		this.ticker = ticker;
		this.name = name;
		this.issuerName = issuerName;
		this.price = price;
		this.votingSharesOutstanding = votingSharesOutstanding;
		this.avgDailyVolume = avgDailyVolume;
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public String getName() {
		return name;
	}

	public String getIssuerName() {
		return issuerName;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public Long getVotingSharesOutstanding() {
		return votingSharesOutstanding;
	}

	public Long getAvgDailyVolume() {
		return avgDailyVolume;
	}

}
