Feature: Limit changes and approval workflow
  Firm limits change through an approval process with a cooling-off window for large changes.

  @S015
  Scenario: Large limit loosening requires cooling-off period before activation
    # Mirrors S015
    Given the demo data
    And QUARANTINE_EXPIRY_MINUTES is currently 30
    When sup-1 asks to change QUARANTINE_EXPIRY_MINUTES to 60
    Then the change needs 3 approvals
    When sup-2 approves the change
    And comp-1 approves the change
    And exec-1 approves the change
    Then the change is AWAITING_ACTIVATION
    When 23 hours and 59 minutes pass
    Then the change is AWAITING_ACTIVATION
    And the active value of QUARANTINE_EXPIRY_MINUTES is 30
    When 1 minute passes
    Then the change is ACTIVE
    And the active value of QUARANTINE_EXPIRY_MINUTES is 60
