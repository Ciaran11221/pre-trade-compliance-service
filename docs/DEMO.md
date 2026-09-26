# Demo: run it and watch the rules work

Eight scenes to run against the service on your own machine. Every response quoted here came from a real run of this code on 26 Sep 2026. Order and request ids will differ on your run.

## 1. Start it

Docker Desktop must be running.

| Step | Command |
|---|---|
| Start the database | `docker compose up -d` |
| Start the service | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Check it is up | open http://localhost:8080/actuator/health, expect `{"status":"UP"}` |

If startup fails with `Migration checksum mismatch`, your local database was made by an older build. `docker compose down -v` deletes it (demo data only), then start again.

## 2. Get tokens

The service trusts a signed token saying who you are and your roles. In a second terminal, one per person:

| Person | Role | Command |
|---|---|---|
| anne | TRADER | `./mvnw -q test-compile exec:java -Dexec.mainClass=io.github.ciaran11221.compliance.support.TokenTool -Dexec.classpathScope=test -Dexec.args="anne TRADER"` |
| brian | TRADER | same, with `-Dexec.args="brian TRADER"` |
| sup-1 | SUPERVISOR | same, with `-Dexec.args="sup-1 SUPERVISOR"` |
| sup-2 | SUPERVISOR | same, with `-Dexec.args="sup-2 SUPERVISOR"` |

Each prints one long line starting `eyJ`. That is the token. Tokens expire; add a lifetime in minutes to keep one longer, e.g. `-Dexec.args="anne TRADER 120"`. A 401 means make a new one.

## 3. Open the Swagger page

Open http://localhost:8080/swagger-ui/index.html. Click **Authorize**, paste a token, click **Authorize** again. Then open a route, click **Try it out**, fill in the body and click **Execute**. To act as someone else, click **Authorize**, **Logout**, and paste their token.

## The demo data

| Fund | id | Assets | Cash | Holds |
|---|---|---|---|---|
| HGF | 1 | $1,000M | $100M | KSTL 450,000 ($45M), NRTH 500,000 ($75M), VLCN 300,000 ($75M) |
| WVF | 2 | $1,000M | $100M | KSTL 450,000 ($45M), QSTN 250,000 ($115M), TDRA 575,000 ($115M) |

KSTL is $100 a share. ZPHR is on the restricted list. HGF already has $150M in positions over 5% of the fund; WVF has $230M.

## Scene 1: the worked example passes

As **anne**, `POST /api/orders`:

    {"clientOrderId":"demo-hgf-1","fundId":1,"side":"BUY","ticker":"KSTL","quantity":100000}

You get `"status":"PASS"`, with one result per rule. The diversification result reads:

> post-trade over-5% issuer positions total $205,000,000.00 (20.5% of fund assets); either at or under the 25% limit, or unchanged from the $150,000,000.00 pre-trade total, so the buy passes.

KSTL was $45M (4.5%). The $10M buy takes it to $55M (5.5%), so it joins the over-5% group: $150M + $55M = $205M. The law allows 25%.

**Send the same request again.** You get the same answer back and no second order is stored. That is how a network retry is handled.

## Scene 2: the same buy blocks for a fund already near the limit

As **anne**, the same buy for WVF (`"clientOrderId":"demo-wvf-1","fundId":2`, rest unchanged). `"status":"BLOCK"`:

> post-trade over-5% issuer positions total $285,000,000.00 (28.5% of fund assets), above the 25% limit and up from $230,000,000.00 pre-trade, so this buy grows the over-limit group.

Same order, different fund, different answer. The rule looks at the whole fund, not the one trade.

## Scene 3: restricted stock

As **anne**, buy 1,000 ZPHR for HGF. BLOCK:

> ZPHR is on the restricted list, so this order is blocked regardless of side or size.

## Scene 4: an opposite-side order is held for a second person

Within 5 minutes of scene 1, as **anne**, sell KSTL for HGF (`"side":"SELL"`, any quantity). You get `"status":"QUARANTINED"` with:

    "quarantine":{"reason":"OPPOSITE_SIDE","matchedOrderId":2, ... "expiresAt": 30 minutes later}

Buying and selling the same stock for the same fund minutes apart is usually a mistake, so no rule runs until a supervisor looks. After 5 minutes the same sell goes straight to the rules.

## Scene 5: selling more than the fund owns

As **anne**, sell 1,000,000 NRTH for HGF. BLOCK:

> sell of 1,000,000 shares of NRTH exceeds the 500,000 shares available (holding of 500,000 minus 0 in pending sells).

## Scene 6: two traders, one order

Within 5 minutes of scene 1, as **brian**, send anne's scene 1 order with a new id (`"clientOrderId":"demo-brian-1"`). `"status":"QUARANTINED"`, reason `POSSIBLE_DUPLICATE`, matched to anne's order.

- As **anne**, `POST /api/quarantine/{id}/release` with brian's order id: **403**. Traders cannot release.
- As **sup-1**, `POST /api/quarantine/{id}/reject`: `"status":"REJECTED"`. Or `/release`, which runs the rules and stores a decision.
- Leave one alone for 30 minutes and it reads `EXPIRED`, treated as rejected.

## Scene 7: a fill changes the fund

As **anne**, `POST /api/orders/{id}/fill` on scene 1's order, no body. `"status":"FILLED"`. HGF's cash is now $90,000,000 and its KSTL holding 550,000. Wait 5 minutes (or the next buy is held as a possible duplicate), then send scene 1's buy with a new id: the diversification result now starts from $205,000,000 pre-trade and ends at 21.5%, because KSTL is now $55M of the fund before the buy, not $45M.

## Scene 8: changing a limit

As **sup-1**, `POST /api/limit-changes`:

| Body | Result |
|---|---|
| `{"key":"OVER_LIMIT_BUCKET_PCT","newValue":40,"reason":"demo"}` | **422**: `OVER_LIMIT_BUCKET_PCT must be <= 25`. The law's 25% lives in code; no approval can pass it |
| `{"key":"OVER_LIMIT_BUCKET_PCT","newValue":20,"reason":"demo tighten"}` | **201**, `"direction":"TIGHTEN"`, `"requiredApprovals":1`, `"status":"PENDING"` |

Then `POST /api/limit-changes/{id}/approvals`:

- as **sup-1** (the requester): **403**, `the requester may not approve their own request.`
- as **sup-2**: `"status":"ACTIVE"`. `GET /api/limits` now shows `"OVER_LIMIT_BUCKET_PCT":20.0000`.

Now try loosening it back to 25. After scene 7, HGF sits at 20.5%, above the new 20%, so going back to 25 would hide a breach that exists today. That makes it a large loosening: 3 approvals including one COMPLIANCE, and a 24-hour wait before it takes effect. The tests move a fake clock to check the 24 hours; on a live run you would wait.

## Start again

Stop the service (Ctrl+C), then `docker compose down -v` and `docker compose up -d`. The demo data reloads on the next start.
