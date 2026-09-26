Feature: Diversification rule compliance
  The diversification rule limits over-5% positions to 25% of the fund's assets.

  @S001
  Scenario: Fund at 20.5 percent in over-5% positions passes
    # Mirrors S001
    Given fund HGF is set up as in fixture "hgf-150m-over-5"
    And fund HGF has total assets of $1,000,000,000
    # 450,000 sh x $100 = $45,000,000
    And fund HGF holds $45,000,000 of KSTL
    # 500,000 sh x $150 = $75,000,000 + 300,000 sh x $250 = $75,000,000 = $150,000,000
    And fund HGF already has $150,000,000 in positions over 5%
    # 100,000 sh x $100 = $10,000,000
    When anne buys $10,000,000 of KSTL for fund HGF
    Then the order is PASS
    And the diversification check is PASS
    # 450,000 + 100,000 = 550,000 sh x $100 = $55,000,000 + $150,000,000 = $205,000,000 / $1,000,000,000 = 20.5%
    And the over-5% group is 20.5% of the fund

  @S002
  Scenario: Fund at 28.5 percent in over-5% positions blocks
    # Mirrors S002
    Given fund WVF is set up as in fixture "wvf-230m-over-5"
    And fund WVF has total assets of $1,000,000,000
    # 450,000 sh x $100 = $45,000,000
    And fund WVF holds $45,000,000 of KSTL
    # 575,000 sh x $200 = $115,000,000 + 250,000 sh x $460 = $115,000,000 = $230,000,000
    And fund WVF already has $230,000,000 in positions over 5%
    # 100,000 sh x $100 = $10,000,000
    When anne buys $10,000,000 of KSTL for fund WVF
    Then the order is BLOCK
    And the diversification check is BLOCK
    # 450,000 + 100,000 = 550,000 sh x $100 = $55,000,000 + $230,000,000 = $285,000,000 / $1,000,000,000 = 28.5%
    And the over-5% group is 28.5% of the fund

  @S003
  Scenario: Sell of an over-5% position is not applicable to diversification
    # Mirrors S003
    Given fund CBF is set up as in fixture "cbf-6pct-in-kstl"
    # 600,000 sh x $100 = $60,000,000 / $1,000,000,000 = 6%
    And fund CBF holds 6% of its assets in KSTL
    # 100,000 sh x $100 = $10,000,000
    When anne sells $10,000,000 of KSTL for fund CBF
    Then the order is PASS
    And the diversification check is NOT_APPLICABLE
