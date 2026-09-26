# pre-trade-compliance-service

Checks a fund's buy and sell orders before they reach a broker: the restricted list, the US Investment Company Act of 1940 diversification test (75-5-10), the fund's cash, and order size against normal trading volume. Orders that look like a duplicate, or come from someone marked out of office, are held until a second person releases them. The firm's own limits change only through approvals scaled to how much money a change would free. Every decision and approval is stored, and the database refuses to edit or delete them.

A portfolio project. The funds, stocks and people are fictional.

## The problem

A fund manager wants to buy $10M of a stock for a $1,000M fund. Once the order reaches a broker it cannot be taken back. Before it goes, it has to be checked against the law, the firm's limits and the fund's cash, and the check has to hold when two people act at the same moment, when an order is sent twice, when someone is away, and when someone wants to loosen a limit.

## What it does

| Step | What happens |
|---|---|
| Order in | `POST /api/orders`. The same `clientOrderId` with the same body returns the stored answer; with a different body, 409. |
| One at a time | The fund's row is locked for the rest of the check, so two orders cannot spend the same cash. |
| Held for a second person | Sender marked out of office; or the same fund and stock, same side and a similar size, within the last few minutes; or the opposite side in that window. |
| Rules | Restricted list (BLOCK), 75-5-10 (BLOCK), cash net of pending orders (BLOCK), a sell above the holding net of pending sells (BLOCK), size above a share of average daily volume (REVIEW). Any BLOCK blocks, else any REVIEW reviews, else PASS. |
| Stored | The order, the decision, each rule's result, and the settings, holdings and prices it was based on, so a decision can be explained after limits change. |
| Filled | `POST /api/orders/{id}/fill`, under the same fund lock: a buy adds the shares to the holding and takes their cost from cash, a sell does the reverse. The next order is checked against the new position. |

**Worked example (a test, word for word).** Fund A: $1,000M, holds $45M of stock X. Buy $10M of X: now $55M, 5.5%, so X joins the group of positions over 5%.
- The fund already has $150M over 5%: total $205M, 20.5%. PASS.
- The fund already has $230M over 5%: total $285M, 28.5%. BLOCK.
- The fund is at 6% in X after a price rise and sells X: PASS. The rule applies to buys.

### Limit changes

| Change | Approvals | Takes effect |
|---|---|---|
| Tightening | 1 | At once |
| Loosening | 2 | At once |
| Large loosening: frees at least the lower of $50M or 1% of the affected funds' assets, or hides a breach that exists today | 3, one from COMPLIANCE | After 24 hours, cancellable until then |

The requester never approves their own request. At least one approver is from another team. No role skips a step. A request past the legal limit is refused (422) and the attempt is recorded. Approving a request whose starting value has changed is refused as stale (409). While one change to a setting is waiting out its 24 hours, a second change to that setting is refused until the first is cancelled or takes effect.

### API

| Route | Roles |
|---|---|
| `POST /api/orders` | TRADER |
| `GET /api/orders/{id}` | TRADER, SUPERVISOR, COMPLIANCE |
| `GET /api/funds/{fundId}/orders?page=` | TRADER, SUPERVISOR, COMPLIANCE |
| `POST /api/orders/{id}/fill`, `/cancel` | TRADER |
| `GET /api/quarantine?assignedToMe=` | SUPERVISOR, COMPLIANCE |
| `POST /api/quarantine/{orderId}/release`, `/reject` | SUPERVISOR, not the sender, not out of office |
| `GET /api/limits` | any signed-in user |
| `POST /api/limit-changes` | SUPERVISOR, COMPLIANCE, EXECUTIVE |
| `GET /api/limit-changes/{id}` | SUPERVISOR, COMPLIANCE, EXECUTIVE |
| `POST /api/limit-changes/{id}/approvals`, `/cancel` | SUPERVISOR, COMPLIANCE, EXECUTIVE |
| `PUT /api/staff/{id}/out-of-office` | the person themselves, or SUPERVISOR |
| `GET /api/me` | any signed-in user |
| `GET /actuator/health` | public |

Errors are RFC 7807 problem details: 400, 401, 403 with the reason, 404, 409, 422.

## Run it

Needs Java 17 and Docker.

| Task | Command |
|---|---|
| Build and run every test | `./mvnw verify` |
| Start a local database | `docker compose up -d` |
| Run the service | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Get a local token for `anne` as a TRADER | `./mvnw -q test-compile exec:java -Dexec.mainClass=io.github.ciaran11221.compliance.support.TokenTool -Dexec.classpathScope=test -Dexec.args="anne TRADER"` |

The `local` profile loads demo funds, stocks and staff and ships a development-only signing key. Outside it, the service will not start without `JWT_SECRET`. Sign-in itself (passwords, single sign-on) is the firm's existing system; this service trusts a correctly signed token.

With the service running on the `local` profile, http://localhost:8080/swagger-ui/index.html lists every route and lets you send requests from the browser. [docs/DEMO.md](docs/DEMO.md) walks through eight scenes to try, and [docs/LEARNING.md](docs/LEARNING.md) gives an order to read the code in, with changes to try that a test will catch.

## Design decisions

**Two people, one order.**
- **Problem:** two traders send the same buy minutes apart. Each order passes the rules alone, and the fund buys twice.
- **Decision:** an order resembling one sent in the last few minutes is held until a supervisor who is not the sender releases or rejects it.
- **Why:** the rules can't tell a mistake from an intended second order; a person can.

**The second person may not be in.**
- **Problem:** a held order waits for a supervisor who is on leave. Separately, a login belonging to someone on leave may be in someone else's hands.
- **Decision:**
  - A held order is open to any in-office supervisor other than the sender. If none is in, it is assigned to the sender's named backup, then to compliance.
  - Anyone marked out of office cannot release, reject or approve.
  - Their own orders are held.
- **Why:** a release that waits for someone on holiday isn't a control, and an action from an absent person's login is the one most likely to be stolen.

**Loosening costs more approvals the more money it frees.**
- **Problem:** one person loosening a limit can switch off a check for every fund at once.
- **Decision:** approvals scale with the extra dollars a change would allow, stored with the request as an impact preview. The large tier waits 24 hours.
- **Why:** a fixed approval count treats a $1,000 change and a $1,000M change the same. Tightening stays at one approval, so an urgent tightening is already fast and needs no emergency bypass.

**Legal limits in code, firm limits in settings.**
- **Problem:** if the law's 5%, 10% and 25% were settings, an approved request could move them past the law.
- **Decision:** the legal values live in code. The firm's own limits are settings at or below them, and a request past a legal value is refused before approval.
- **Why:** changing the law's numbers should take a code change, review and CI, not a form.

**The database protects the audit tables, not just the API.**
- **Problem:** anyone with direct database access could edit a past decision.
- **Decision:** triggers reject UPDATE and DELETE on every audit table. Current state (an order's status, a limit's active value) is read from the latest row.
- **Why:** a record of decisions is only worth something if nobody can rewrite it, including a developer with a database login.

**Look back rather than hold every order.**
- **Problem:** holding every order until a second person looks at it would stop a trading desk.
- **Decision:** only orders that resemble a recent one, or come from someone marked away, are held.
- **Why:** that keeps the second-person check on the orders where it catches something.

**An agreed list of what must be tested.**
- **Problem:** a rule can be added with no test that exercises it, and nothing notices.
- **Decision:**
  - Every rule, limit-change rule and quarantine rule has a scenario file, and the build fails if a rule has none.
  - The build also fails if `CLAUDE.md`'s map of the code misses a package, or if a route has no row in the role table.
- **Why:** the checks that matter are enforced by the build rather than by memory.

## Not done

- Government securities and holdings in other investment companies are exempt from 75-5-10 in the real rule. Not modelled.
- Voting control is checked per security, not pooled across an issuer's share classes.
- The reading of 75-5-10 at the moment of purchase (a fund pushed over by price moves is not forced to sell) is an interpretation. It has not been reviewed by a compliance professional.
- A fill applies at one price for the whole quantity.
- No market data, broker routing, partial fills, notifications, or frontend.
- Out-of-office status is a flag on the staff table, not a feed from an HR system.

## Roadmap

| Item | Why |
|---|---|
| HR feed for out-of-office status | Replaces the staff table flag with the firm's real source |
| Review queue for REVIEW outcomes | REVIEW is recorded today but nobody works it |
| Pooled voting-share test across an issuer's share classes | Voting control is checked per security today |
| Block-order fill allocation, a second service that calls this one first | Splits one large order across funds after it passes compliance |
| Kafka order intake | How orders would arrive on a live desk |
| Compliance-professional review of the purchase-time reading of 75-5-10 | It is an interpretation, labelled as one |

## How it was built

    Ciaran  ->  Opus  ->  Sonnet  ->  Haiku
    decides     specs,    builds      builds
                reviews   the hard    the routine
                          parts       parts

Built with Claude models. Each milestone had a GitHub issue stating what "done" means. Sonnet or Haiku built it on a branch. Opus read the full diff, re-ran the test suite and fixed what it found. CI ran on every pull request, and each was squash-merged. [docs/WORKLOG.md](docs/WORKLOG.md) has one row per pull request: who wrote it, who reviewed it, and the test count.

The review found at least one problem per milestone that the tests of the time did not catch. Each fix came with a test:

| Milestone | Found in review |
|---|---|
| Skeleton | The Maven wrapper was not executable on Linux, so CI could not run it |
| Database | Demo data in a versioned migration would have stopped the app starting on an existing database |
| Security | A too-short signing secret let the app start, then refused every login |
| Scenario corpus | A comment's wording would have sent the next milestone into a compile error |
| Rules | A decision's reason text changed with the server's language settings |
| Limit changes | An approved loosening waiting its 24 hours would silently undo a tightening made during the wait |
| Order intake | The same order id sent for two funds at once gave the second caller a server error, because a failed insert ends the database transaction |
| Quarantine | Demo staff ids in a versioned migration would have made every real supervisor a trader |

`./mvnw verify` on the tagged commit: 257 tests, 0 failures. Scenario index: [docs/SCENARIOS.md](docs/SCENARIOS.md).

## Licence

MIT.
