package io.github.ciaran11221.compliance.reference;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "holding")
public class Holding {

	@EmbeddedId
	private HoldingId id;

	@ManyToOne(fetch = FetchType.LAZY)
	@MapsId("fund")
	@JoinColumn(name = "fund_id", nullable = false)
	private Fund fund;

	@ManyToOne(fetch = FetchType.LAZY)
	@MapsId("security")
	@JoinColumn(name = "security_id", nullable = false)
	private ListedSecurity security;

	@Column(nullable = false)
	private long quantity;

	protected Holding() {
	}

	public Holding(Fund fund, ListedSecurity security, long quantity) {
		this.fund = fund;
		this.security = security;
		this.id = new HoldingId(fund.getId(), security.getId());
		this.quantity = quantity;
	}

	public HoldingId getId() {
		return id;
	}

	public Fund getFund() {
		return fund;
	}

	public ListedSecurity getSecurity() {
		return security;
	}

	public long getQuantity() {
		return quantity;
	}

}
