Feature: Order compliance checks
  Orders are checked against restricted securities, cash, and position limits.

  @S004
  Scenario: Buy of a restricted security blocks
    # Mirrors S004
    Given fund HGF is set up as in fixture "hgf-zphr-restricted"
    And ZPHR is on the restricted list
    # 10,000 sh x $50 = $500,000
    When anne buys $500,000 of ZPHR for fund HGF
    Then the order is BLOCK
    And the restricted-list check is BLOCK

  @S005
  Scenario: Buy that exceeds available cash after pending orders blocks
    # Mirrors S005
    Given fund HGF is set up as in fixture "hgf-cash-10m-pending-6m"
    And fund HGF has $10,000,000 of cash
    # 40,000 sh x $150 = $6,000,000
    And fund HGF has a pending buy of $6,000,000 of NRTH
    # 50,000 sh x $100 = $5,000,000
    When anne buys $5,000,000 of KSTL for fund HGF
    Then the order is BLOCK
    And the cash check is BLOCK
