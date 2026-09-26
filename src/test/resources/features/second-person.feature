Feature: Second-person quarantine and release
  Duplicate and suspicious orders are quarantined until a different person releases them.

  @S023
  Scenario: Same-side order within the lookback window is quarantined as a possible duplicate
    # Mirrors S023
    Given the demo data
    When anne buys 1,000 shares of KSTL for fund HGF
    And brian buys 1,050 shares of KSTL for fund HGF
    Then anne's order is PASS
    And brian's order is QUARANTINED
    And brian's order is held as a possible duplicate

  @S026
  Scenario: The sender of a quarantined order may not release their own quarantine
    # Mirrors S026
    Given the demo data
    When anne buys 1,000 shares of KSTL for fund HGF
    And brian buys 1,050 shares of KSTL for fund HGF
    And brian tries to release brian's order as a SUPERVISOR
    Then the request is refused with status 403
    And the refusal says "may not release or reject its own quarantine"
    And brian's order is still QUARANTINED
