# Learning from this code

A reading order, from one order arriving to limits changing, with the file for each step and a change to try that a test will catch. Run [DEMO.md](DEMO.md) first, so each file matches something you have already seen happen.

`CLAUDE.md` at the top of the repo maps every package. Paths below are under `src/main/java/io/github/ciaran11221/compliance/` unless they start with `src/`.

## Before you start

- `./mvnw verify` runs every test. It needs Docker, because the tests use a real Postgres in a container (Testcontainers).
- To run one test class: `./mvnw test -Dtest=CashRuleTest`.
- Each "Try this" below breaks something on purpose. Run the named test, watch it fail, then undo the change with `git checkout -- <file>`.

## 1. A request comes in: security

| Read | What it teaches |
|---|---|
| `security/SecurityConfig.java` | Every route is denied unless it says otherwise. Tokens are checked for a valid signature; roles come from the token's `roles` claim |
| `orders/OrderController.java` | `@PreAuthorize("hasRole('TRADER')")` on each method. The controller only unpacks the request and calls the service |
| `src/test/resources/route-access.csv` and `security/RouteAccessMatrixTest` (under `src/test/java/...`) | The build lists every route from Spring and fails if one has no row in the table, then calls each route as each role |

**Try this:** in `OrderController`, change `submit`'s `@PreAuthorize` to allow `SUPERVISOR` too. Run `RouteAccessMatrixTest`: a supervisor posting an order is now allowed, and the table says it must not be.

## 2. The order is checked: `orders/OrderService.submit`

Read it top to bottom. The order of the steps is the design:

1. **Lock the fund row** (`lockFund`, `SELECT ... FOR UPDATE`). A second order for the same fund waits here until this one commits, so two orders can't both spend the same cash.
2. **Same clientOrderId seen before?** Return the stored answer. The lock comes first so that two identical first sends queue up rather than race.
3. **Hold it for a second person?** (`checkQuarantine`): sender out of office, or a similar or opposite order in the last few minutes.
4. **Build the context** (`buildContext`): holdings, prices, the restricted list, pending orders and the active limits, all read inside the lock.
5. **Run the rules** and **store** the order, the decision, every rule's result, and a snapshot of what they were based on.

**Try this:** in `OrderRepository.lockFund`, delete ` FOR UPDATE`. Run `PendingExposureAndCashRaceTest`: two buys that together exceed cash now both pass.

## 3. The rules: `rules/`

Each rule is a plain class: `evaluate(OrderContext) -> RuleResult`, with no database and no Spring. That is why they are easy to test.

| Read | Rule |
|---|---|
| `RestrictedListRule` | The simplest: on the list, BLOCK |
| `CashRule` | Order value against cash minus pending buys |
| `HoldingRule` | A sell against the holding minus pending sells. The newest rule; read its commit to see everything adding a rule touched |
| `OrderSizeRule` | Above a share of average daily volume, REVIEW (a person looks), not BLOCK |
| `DiversificationRule` + `IssuerOverLimitCalculator` | The 1940 Act 75-5-10 test. The only hard one: positions are summed by issuer, and the fund may hold up to 25% in positions over 5% |
| `ComplianceEngine` | Combines results: any BLOCK blocks, else any REVIEW reviews, else PASS |

Money is `BigDecimal` everywhere, never `double`. Look for comparisons written as `a × 100 > b × limit` rather than `a / b > limit`: dividing first loses exactness.

**Try this:** add a sixth rule class that always returns PASS and mark it `@Component`. Run `ScenarioCoverageTest`: the build refuses a rule with no scenario file. Now add one under `src/test/resources/scenarios/` by copying `S004`.

## 4. Scenarios: tests as data

`src/test/resources/scenarios/` holds one YAML file per case: the fund, the order, the expected outcome. `docs/SCENARIOS.md` is the generated index. `rules/RuleScenarioTest` runs every `kind: rule` file against the real rules. The limit-change and quarantine files run over HTTP against a real database.

**Try this:** in `S001`, change `outcome: PASS` to `BLOCK`. Run `RuleScenarioTest` and read the failure message.

## 5. Nothing is edited: the audit tables

Open `src/main/resources/db/migration/V2__audit_insert_only.sql`. A database trigger rejects `UPDATE` and `DELETE` on every audit table, so even someone with a database login can't rewrite a decision. An order's current status is its latest event, not a column that changes.

**Try this:** `docker compose exec postgres psql -U compliance -d compliance -c "delete from decision"`. The trigger refuses.

## 6. Held orders: `quarantine/QuarantineService`

`release` takes the same fund lock as a new order, runs the rules, and stores the decision through the same code as intake. `reject` records the resolution. Expiry is worked out from the clock on every read, so correctness never waits for the scheduled job in `QuarantineExpiryJob`.

**Try this:** in `QuarantineService`, remove the check that the caller is not the sender. Run `QuarantineScenarioTest`: S026 (self-release) fails.

## 7. Changing a limit: `limits/LimitChangeService`

| Read | What it teaches |
|---|---|
| `rules/LimitKey` | Each setting's legal bound and which direction is looser, in code |
| `requestChange` | Bound check first (422), then the impact preview (`ImpactPreviewCalculator`: how many dollars a change frees), then how many approvals (`RequiredApprovalsCalculator`) |
| `approve` | Refuses self-approval, out-of-office approvers and stale requests; activates once enough approvals from more than one team arrive |
| `lockSettingKey` in `LimitChangeRepository` | A Postgres advisory lock: two requests for the same setting take turns, even though they are different rows |

**Try this:** in `approve`, delete the self-approval check. Run `LimitChangeScenarioTest`: S012 fails.

## 8. Concurrency, in one place

Three bugs this project met, each with a test that fails without its fix:

| Bug | Where | Test |
|---|---|---|
| Two orders spend the same cash | `OrderRepository.lockFund` | `PendingExposureAndCashRaceTest` |
| Lost update: two fills both read $200M, one write overwrites the other | the fund lock in `OrderService.fill` | `OrderFillHoldingsAndCashTest.twoFillsForOneFundAtTheSameMomentBothComeOffCash` |
| A failed insert ends the Postgres transaction, so the next query fails too | `ON CONFLICT DO NOTHING` in `OrderRepository.insertOrder` | `OrderIntakeIdempotencyTest.sameNewClientOrderIdRacedAcrossTwoFundsGivesOneRowAndA409NotA500` |

The tests use `CyclicBarrier` to release two threads at the same moment, and real commits. Tests that roll back can't show a race between two connections.

## Where the history is

`docs/WORKLOG.md` has one row per pull request. Each pull request on GitHub states what it changed, what was not verified and what review found. `git log --stat` shows which files each change touched.
