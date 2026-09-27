# How it decides

The service's decisions as IF / ELSE logic, one plain-English line per rule, and the line of code that makes each call. Read this first; open the code when you want the detail.

Every example uses the demo data in [DEMO.md](DEMO.md): two $1,000M funds, HGF and WVF, and made-up stocks such as KSTL at $100 a share.

## One order, start to finish

`orders/OrderService.java` runs this for every `POST /api/orders`:

```text
IF the request has a missing or bad field     -> 400, and errors[] lists every bad field
LOCK the fund, so one order per fund is checked at a time
IF this clientOrderId was sent before
    same body       -> return the stored answer; no second order is made
    different body  -> 409
IF the sender is marked out of office          -> QUARANTINED
ELSE IF an order for the same fund and stock arrived in the last 5 minutes
        (and was not rejected, expired or cancelled)
    opposite side                               -> QUARANTINED, reason OPPOSITE_SIDE
    same side, size within 10% of the earlier   -> QUARANTINED, reason POSSIBLE_DUPLICATE
ELSE run every rule; each answers PASS, REVIEW, BLOCK or NOT_APPLICABLE
    any rule says BLOCK        -> BLOCK
    ELSE any rule says REVIEW  -> REVIEW
    ELSE                       -> PASS
STORE the order, the decision, each rule's answer, and the settings and fund
position it was based on. Stored rows can never be changed or deleted.
```

- **QUARANTINED** means held until a supervisor releases or rejects it. No rule runs until then.
- **REVIEW** means a person should look, but the order is not stopped. **BLOCK** means it is stopped.
- The combining step is `rules/ComplianceEngine.java`, method `combine`.

## The five rules

### Restricted list (`rules/RestrictedListRule.java`)

**Plain English:** the firm keeps a list of stocks nobody may trade, for example because staff know unpublished news about the company.

```text
IF the stock is on the restricted list -> BLOCK   (buy or sell, any size)
ELSE                                   -> PASS
```

- **Deciding line:** `if (context.restrictedSecurities().contains(ticker))`
- **Example:** ZPHR is on the list, so buying 1,000 ZPHR for HGF blocks.

### Diversification, the 75-5-10 rule (`rules/DiversificationRule.java`)

**Plain English:** don't put too many eggs in one basket. Any company worth more than 5% of the fund is a big position, and the big positions together may not pass 25% of the fund.

```text
IF the fund is not registered as diversified OR the order is a SELL -> NOT_APPLICABLE
position of each company = shares held + pending buys (+ this order, for "after")
a company is big IF its value > 5% of the fund
               OR  the fund holds > 10% of one of its stocks' voting shares
before = total value of big positions, without this order
after  = total value of big positions, with this order
IF after > 25% of the fund AND after > before -> BLOCK
ELSE                                          -> PASS
```

- **Deciding line:** `if (overBucket && grew)`
- **Why "AND after > before":** the law is tested at the moment of buying. A fund pushed over 25% by price rises is not forced to sell, and may still buy something that does not grow its big positions. This reading is an interpretation, not yet reviewed by a compliance professional.
- **Example:** KSTL goes from $45M (4.5%) to $55M (5.5%) with a $10M buy, so it becomes a big position.

| Fund | Big positions before | After the buy | Result |
|---|---|---|---|
| HGF | NRTH $75M + VLCN $75M = $150M | $150M + KSTL $55M = $205M, 20.5% | PASS |
| WVF | QSTN $115M + TDRA $115M = $230M | $230M + KSTL $55M = $285M, 28.5% | BLOCK |

### Cash (`rules/CashRule.java`)

**Plain English:** a buy must be paid for with cash the fund has not already promised to other buys.

```text
IF the order is a SELL -> NOT_APPLICABLE
order value = shares x price
available   = fund cash - value of pending buys
IF order value > available -> BLOCK
ELSE                       -> PASS
```

- **Deciding line:** `boolean blocked = orderValue.compareTo(available) > 0;`
- A pending sell frees no cash until it is filled.
- **Example:** HGF has $100M cash. Once the $10M KSTL buy is pending, the next buy has $90M available.

### Holding (`rules/HoldingRule.java`)

**Plain English:** a fund cannot sell shares it does not have, or shares it has already promised to another sell.

```text
IF the order is a BUY -> NOT_APPLICABLE
available = shares held - shares in pending sells of the same stock
IF shares to sell > available -> BLOCK
ELSE                          -> PASS
```

- **Deciding line:** `boolean blocked = requested > available;`
- **Example:** HGF holds 500,000 NRTH, so selling 1,000,000 NRTH blocks. Selling exactly 500,000 passes.

### Order size (`rules/OrderSizeRule.java`)

**Plain English:** an order that is large compared with how much of the stock normally trades in a day can move its price, so a person should look first.

```text
IF shares > 10% of the stock's average daily volume -> REVIEW   (buy or sell)
ELSE                                                -> PASS
```

- **Deciding line:** `boolean review = quantity.multiply(HUNDRED).compareTo(advPct.multiply(avgDailyVolume)) > 0;`
- The code compares `shares x 100` with `10 x volume`, so it never needs to divide to decide.
- **Example:** KSTL's average daily volume is 2,000,000 shares. 100,000 shares is 5%, so PASS. 250,000 is 12.5%, so REVIEW.

## The limits these rules use

Firm settings, changed only through the approval process in `limits/`. Each has a hard bound in code that no approval can pass; for the three legal limits, that bound is the law's own figure.

| Setting | Default | Used by |
|---|---|---|
| `ISSUER_LIMIT_PCT` | 5 | Diversification: what counts as a big position |
| `VOTING_LIMIT_PCT` | 10 | Diversification: voting-share test |
| `OVER_LIMIT_BUCKET_PCT` | 25 (the legal maximum) | Diversification: the ceiling for big positions together |
| `ORDER_SIZE_ADV_PCT` | 10 | Order size |
| `LOOKBACK_MINUTES` | 5 | Quarantine: how far back to look for a matching order |
| `SIMILARITY_PCT` | 10 | Quarantine: how close in size counts as a duplicate |

## Words used on this page

| Word | Means |
|---|---|
| Fund | A pool of investors' money that a manager invests |
| Position | How much of one company the fund owns, in dollars |
| Pending | An order that passed but is not filled yet, or is held in an open quarantine |
| Filled | The broker has actually bought or sold the shares |
| Average daily volume | How many shares of a stock normally change hands in a day |
| NOT_APPLICABLE | The rule does not apply to this order, for example the cash rule on a sell |

## Adding a rule

A new rule is one class in `rules/` that implements `ComplianceRule`, plus one scenario file in `src/test/resources/scenarios/`. The build fails if a rule has no scenario. Add its entry here in the same shape: plain English, the IF / ELSE, the deciding line and an example.
