# Repo map

Read this first. It says where things are so you can open the few files a task needs rather than the whole repo. `RepoMapTest` fails the build if a code package is missing from the table below.

## What this service does

Checks a fund's buy and sell orders before they reach a broker: the restricted list, the US 1940 Act 75-5-10 diversification rule, cash, and order size. Orders that look like duplicates are quarantined until a second person releases them. The firm's limits change only through an approval process scaled to how much money a change would free up. Decisions and approvals are insert-only.

## Where things live

| Package (`io.github.ciaran11221.compliance.*`) | Holds |
|---|---|
| `compliance` | Application entry point |
| `me` | `GET /api/me`: who the caller's token says they are |
| `reference` | Funds, securities, holdings, staff: entities and repositories |
| `security` | Token checking, deny-by-default rules, known-staff check, 401/403 bodies |
| `rules` | The compliance rules (restricted list, diversification, cash, order size), the engine that combines their results, and the firm-limits read path |
| `limits` | Limit changes: request, impact preview, approvals scaled by money freed, cooling-off, cancel. Requests, approvals, activations and refusals are insert-only |
| `orders` | Order intake: idempotent create with a per-fund lock, the stored decision (rule results, settings and fund-state snapshot), read, fill and cancel. Orders, events, decisions and rule results are insert-only |

| Path | Holds |
|---|---|
| `src/main/resources/db/migration/` | Schema (V1), audit insert-only triggers (V2), default limit settings (V3). New migrations are V4 and up. Never edit an applied one. |
| `src/main/resources/db/seed/R__seed.sql` | Fictional demo funds, securities and staff. Loaded by the `local` and `test` profiles only. |
| `src/test/resources/route-access.csv` | Which roles may call which route. Every new route needs a row, or the build fails. |
| `src/test/resources/scenarios/` | The scenario corpus: one YAML file per scenario. |
| `src/test/java/io/github/ciaran11221/compliance/scenario/` | Scenario records, the loader, validation, the index generator, and RequiredCoverage (how a later rule provider plugs into ScenarioCoverageTest). |
| `docs/SCENARIOS.md` | Generated index of the scenario corpus. Never edit by hand. |
| `docs/WORKLOG.md` | One row per merged pull request: who wrote it, who reviewed it, test counts. |

## Commands

| Task | Command |
|---|---|
| Full build and tests (needs Docker running) | `./mvnw verify` |
| Local database | `docker compose up -d` |
| Run locally | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Issue a local token | `./mvnw -q test-compile exec:java -Dexec.mainClass=io.github.ciaran11221.compliance.support.TokenTool -Dexec.classpathScope=test -Dexec.args="anne TRADER"` |
| Regenerate the scenario index | `./mvnw test -Dtest=ScenarioIndexTest -Dscenarios.regenerate=true` |

## Rules

- Money and percentages are `BigDecimal` / `NUMERIC`. Never `double`.
- Time comes from the injected `Clock` bean. Tests move a fixed clock; they never sleep.
- Audit tables are insert-only, enforced by database triggers. Current state is read from the latest row, never updated in place.
- Every route carries `@PreAuthorize` (or `@PublicEndpoint`) and a row in `route-access.csv`.
- Legal limits live in code. Firm limits are settings at or below them and change only through the approval process.
- No real company, fund, person or ticker names anywhere.
- One change per branch. Commit messages are Conventional Commits and state what was not verified.
