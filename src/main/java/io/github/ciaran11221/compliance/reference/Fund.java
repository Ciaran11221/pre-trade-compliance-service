package io.github.ciaran11221.compliance.reference;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "fund")
public class Fund {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	@Column(name = "total_assets", nullable = false)
	private BigDecimal totalAssets;

	@Column(nullable = false)
	private BigDecimal cash;

	@Column(nullable = false)
	private boolean diversified;

	protected Fund() {
	}

	public Fund(String code, String name, BigDecimal totalAssets, BigDecimal cash, boolean diversified) {
		this.code = code;
		this.name = name;
		this.totalAssets = totalAssets;
		this.cash = cash;
		this.diversified = diversified;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getTotalAssets() {
		return totalAssets;
	}

	public BigDecimal getCash() {
		return cash;
	}

	public boolean isDiversified() {
		return diversified;
	}

}
