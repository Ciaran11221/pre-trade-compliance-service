package io.github.ciaran11221.compliance.reference;

import java.io.Serializable;
import java.util.Objects;

public class HoldingId implements Serializable {

	private Long fund;

	private Long security;

	protected HoldingId() {
	}

	public HoldingId(Long fund, Long security) {
		this.fund = fund;
		this.security = security;
	}

	public Long getFund() {
		return fund;
	}

	public Long getSecurity() {
		return security;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HoldingId other)) {
			return false;
		}
		return Objects.equals(fund, other.fund) && Objects.equals(security, other.security);
	}

	@Override
	public int hashCode() {
		return Objects.hash(fund, security);
	}

}
